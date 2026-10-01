package com.ejemplo.microservicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.boot.autoconfigure.jdbc.JdbcConnectionDetails;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * INTEGRACION DE VERDAD: Java -> Python -> PostgreSQL, todo real.
 *
 * Estos tests son los que cierran el hueco que dejaban los dobles. Con
 * MockRestServiceServer, Java verifica que envia lo que cree enviar; nada
 * comprueba que Python lo entienda. Un campo renombrado en el servicio de
 * IA llegaria a Java como null y produciria un 500 sin explicacion, y los
 * tests unitarios NO lo detectarian.
 *
 * Aqui no hay dobles: el contenedor levanta la misma imagen que
 * produccion, Flyway migra de verdad y la peticion cruza la red.
 *
 * Tarda mas que el resto del suite (arrancar dos contenedores) a cambio de
 * cubrir la frontera que mas se rompe en la practica.
 */
@SpringBootTest
@AutoConfigureMockMvc
// Los initializers van en @ContextConfiguration, no en @TestPropertySource:
// cada anotacion acepta lo suyo y mezclarlas no compila.
@ContextConfiguration(initializers = IntegracionTestConfig.class)
@TestPropertySource(properties = {
                // validate y Flyway activado: el esquema lo crea la
                // migracion real y Hibernate comprueba que las entidades
                // encajen. Es el mismo camino que produccion.
                "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "seguridad.activada=false"
})
// ORDEN DE EJECUCION FIJADO A PROPOSITO.
//
// Un test que hace fallar la IA deja el circuito breaker ABIERTO, y ahi
// empieza un problema serio: si ese test corre antes que los demas
// (JUnit ordena por defecto de forma alfabetica), todos los siguientes
// reciben CIRCUITO_ABIERTO sin llegar a llamar al servicio. El fallo
// aparece en tests que estan bien, y parece un fallo de integracion
// cuando en realidad es de estado compartido.
//
// @TestMethodOrder(ORDER) + @Order fijan el orden: primero los que usan la IA en
// condiciones normales, y al final los que la dejan caida.
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IntegracionCompletaTest {

    @Autowired
    private MockMvc mockMvc;

    /** Reinicia el circuito entre tests, para que ninguno herede estado. */
    @Autowired
    private io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry breakerRegistry;

    @Autowired
    private io.github.resilience4j.bulkhead.BulkheadRegistry bulkheadRegistry;

    @BeforeEach
    void reiniciarResiliencia() {
        // Bulkhead de tipo SEMAPHORE no tiene reset(): solo el circuit
        // breaker acumula estado que afecte al siguiente test.
        breakerRegistry.circuitBreaker("servicioIA").reset();
    }

    private final ObjectMapper json = new ObjectMapper();

    // ------------------------------------------------------- FRAUDE

    @Order(1)
    @Test
    @DisplayName("Fraude: transaccion sospechosa detectada de punta a punta")
    void fraudeSospechoso() throws Exception {
        String cuerpo = """
                {"monto":9000,"hora":3,"pais":"NG","distancia_km":5200}""";

        mockMvc.perform(post("/api/transacciones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.es_fraude").value(true))
                .andExpect(jsonPath("$.accion").value("BLOQUEADA"))
                .andExpect(jsonPath("$.nivel_riesgo").value("critico"));
    }

    @Order(2)
    @Test
    @DisplayName("Fraude: transaccion legitima aprobada")
    void fraudeLegitimo() throws Exception {
        mockMvc.perform(post("/api/transacciones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"monto":150,"hora":12,"pais":"ES","distancia_km":80}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.es_fraude").value(false))
                .andExpect(jsonPath("$.accion").value("APROBADA"));
    }

    @Order(7)
    @Test
    @DisplayName("Fraude: pais desconocido devuelve 503, no una prediccion inventada")
    void paisDesconocido() throws Exception {
        // El encoder de Python usa handle_unknown='ignore'. Sin el
        // rechazo explicito de PAISES_CONOCIDOS, JP pasaria al vector de
        // ceros y devolveria una prediccion sin senal del pais, con total
        // aspecto de correcta.
        mockMvc.perform(post("/api/transacciones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"monto":100,"hora":12,"pais":"JP","distancia_km":9000}"""))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.codigo").value("IA_NO_DISPONIBLE"));
    }

    // -------------------------------------------------- SENTIMIENTO

    @Order(6)
    @Test
    @DisplayName("Sentimiento: las tres clases del contrato existen y coinciden")
    void sentimientoTresClases() throws Exception {
        // El endpoint devuelve etiquetas en ingles desde el modelo; el
        // servicio las traduce. Si se rompiera esa traduccion, el cliente
        // recibiria "positive" donde espera "positivo" y el contrato
        // estaria roto sin que nada fallara.
        record Caso(String texto, String esperada) {}

        var casos = new Caso[]{
                new Caso("me encantó, llegó rapidísimo y muy barato", "positivo"),
                new Caso("nadie me contestó, un desastre", "negativo"),
                new Caso("quiero saber cuánto tarda el envío a Madrid", "neutro"),
        };

        for (var caso : casos) {
            String respuesta = mockMvc.perform(post("/api/texto")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(
                                    java.util.Map.of("texto", caso.texto()))))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            JsonNode nodo = json.readTree(respuesta);

            assertThat(nodo.get("sentimiento").asText())
                    .as("caso: %s", caso.texto())
                    .isEqualTo(caso.esperada());

            // Las tres probabilidades siempre presentes: es parte del
            // contrato, no un extra.
            assertThat(nodo.get("probabilidades").has("positivo")).isTrue();
            assertThat(nodo.get("probabilidades").has("negativo")).isTrue();
            assertThat(nodo.get("probabilidades").has("neutro")).isTrue();
        }
    }

    // ------------------------------------------- PERSISTENCIA + IA

    @Order(5)
    @Test
    @DisplayName("Alta de usuario, transaccion y consulta con los tres servicios")
    void flujoCompleto() throws Exception {
        // 1. Usuario
        String usuarioJson = mockMvc.perform(post("/api/v1/usuarios")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"integ@ejemplo.com","nombre":"Integ Test",
                                 "contrasena":"contrasena-larga-123"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("integ@ejemplo.com"))
                // El hash NO debe aparecer en ninguna respuesta.
                .andExpect(jsonPath("$.hash_contrasena").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        long usuarioId = json.readTree(usuarioJson).get("id").asLong();

        // 2. Transaccion sospechosa: IA + escritura en la BD real
        mockMvc.perform(post("/api/v1/usuarios/" + usuarioId + "/transacciones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"monto":9000,"hora":3,"pais":"NG","distancia_km":5200}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accion").value("BLOQUEADA"));

        // 3. Consulta: demuestra que llego a PostgreSQL de verdad
        String historial = mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .get("/api/v1/usuarios/" + usuarioId + "/transacciones"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode transacciones = json.readTree(historial);
        assertThat(transacciones).hasSize(1);
        assertThat(transacciones.get(0).get("es_fraude").asBoolean()).isTrue();
        assertThat(transacciones.get(0).get("estado").asText()).isEqualTo("ANALIZADA");
    }

    @Order(8)
    @Test
    @DisplayName("El pais desconocido NO se persiste como si fuera legitimo")
    void paisDesconocidoNoSePersisteComoLimpio() throws Exception {
        String usuarioJson = mockMvc.perform(post("/api/v1/usuarios")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"paisx@ejemplo.com","nombre":"Pais X",
                                 "contrasena":"contrasena-larga-123"}"""))
                .andReturn().getResponse().getContentAsString();
        long usuarioId = json.readTree(usuarioJson).get("id").asLong();

        // La peticion falla (503) porque Python rechaza el pais.
        mockMvc.perform(post("/api/v1/usuarios/" + usuarioId + "/transacciones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"monto":100,"hora":12,"pais":"JP","distancia_km":9000}"""))
                .andExpect(status().isServiceUnavailable());

        // Pero la transaccion se guarda, y lo que importa es COMO:
        // es_fraude NULL significa "nadie la evaluo". Si se guardara como
        // false, un fraude pasaria por aprobado.
        String historial = mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .get("/api/v1/usuarios/" + usuarioId + "/transacciones"))
                .andReturn().getResponse().getContentAsString();

        JsonNode t = json.readTree(historial).get(0);
        assertThat(t.get("es_fraude").isNull())
                .as("es_fraude debe ser null, no false")
                .isTrue();
        assertThat(t.get("estado").asText()).isEqualTo("NO_ANALIZADA");
    }

    // ----------------------------------------- CONTRATO DE CAMPOS

    @Order(3)
    @Test
    @DisplayName("Los nombres de campo que Java envia son los que Python espera")
    void contratoDeNombresDeCampo() throws Exception {
        // Este es EL test que justifica toda la infraestructura: verifica
        // el enlace entre el record de Java y el esquema Pydantic, que es
        // la frontera que se rompe sin avisar.
        //
        // Si alguien renombra "distancia_km" en Python, la peticion se
        // rechaza con 422, Java devuelve 503 y este test falla. Sin el,
        // el cambio pasaria los tests unitarios y llegaria a produccion.
        mockMvc.perform(post("/api/transacciones")
                        .contentType(MediaType.APPLICATION_JSON)
                        // Se manda el JSON con TODAS las claves correctas.
                        .content("""
                                {"monto":150,"hora":12,"pais":"ES","distancia_km":80}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.probabilidad").exists())
                .andExpect(jsonPath("$.modelo").exists())
                .andExpect(jsonPath("$.nivel_riesgo").exists())
                .andExpect(jsonPath("$.accion").exists());
    }

    @Order(4)
    @Test
    @DisplayName("La respuesta de Python trae todos los campos que Java deserializa")
    void contratoDeRespuesta() throws Exception {
        String cuerpo = mockMvc.perform(post("/api/transacciones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"monto":9000,"hora":3,"pais":"NG","distancia_km":5200}"""))
                .andReturn().getResponse().getContentAsString();

        JsonNode nodo = json.readTree(cuerpo);

        // RespuestaFraudePython.java declara estos cuatro. Si Python deja
        // de enviar alguno, el record se llena de null y el NPE aflora
        // como un 500 sin explicacion.
        for (String campo : new String[]{"es_fraude", "probabilidad",
                "nivel_riesgo", "modelo"}) {
            assertThat(nodo.has(campo))
                    .as("Falta '%s' en la respuesta del servicio de IA", campo)
                    .isTrue();
            assertThat(nodo.get(campo).isNull())
                    .as("'%s' vino null", campo)
                    .isFalse();
        }
    }
}