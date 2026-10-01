package com.ejemplo.microservicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ejemplo.microservicio.security.EmisorDeToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jdbc.JdbcConnectionDetails;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

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
        "spring.flyway.enabled=true"
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

    @Autowired
    private EmisorDeToken emisor;

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

    // ------------------------------------------------------- AUTENTICACION

    /**
     * Peticion POST con un token de sesion del usuario indicado.
     *
     * El token lo emite el EmisorDeToken real y lo verifica el JwtDecoder
     * real del contexto, con la misma clave. No se falsea nada, asi que
     * estos tests recorren la autenticacion entera.
     */
    private MockHttpServletRequestBuilder postConToken(String ruta, long usuarioId) {
        return post(ruta).contentType(MediaType.APPLICATION_JSON)
                .header("Authorization",
                        "Bearer " + emisor.emitir(usuarioId, "u@ejemplo.com"));
    }

    private MockHttpServletRequestBuilder getConToken(String ruta, long usuarioId) {
        return get(ruta).header("Authorization",
                "Bearer " + emisor.emitir(usuarioId, "u@ejemplo.com"));
    }

    // ------------------------------------------------------- FRAUDE

    @Order(1)
    @Test
    @DisplayName("Fraude: transaccion sospechosa detectada de punta a punta")
    void fraudeSospechoso() throws Exception {
        String cuerpo = """
                {"componentes":[-9.1698,7.0922,-12.354,4.2431,-7.1764,-3.3866,-8.058,6.4429,-2.413,-6.1349,2.8267,-6.3098,-0.623,-7.2799,0.9242,-4.2155,-7.1717,-2.5503,0.5964,0.8167,0.9262,-0.8177,-0.1504,-0.0394,0.4856,-0.2643,1.1597,0.2328],"monto":99.99,"hora":3,"pais":"NG","distancia_km":5200}""";

        mockMvc.perform(postConToken("/api/transacciones", 1L)
                        .content(cuerpo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.es_fraude").value(true))
                .andExpect(jsonPath("$.accion").value("REQUIERE_REVISION"))
                .andExpect(jsonPath("$.nivel_riesgo").value("alto"));
    }

    @Order(2)
    @Test
    @DisplayName("Fraude: transaccion legitima aprobada")
    void fraudeLegitimo() throws Exception {
        mockMvc.perform(postConToken("/api/transacciones", 1L)
                        .content("""
                                {"componentes":[-0.2743,0.1401,2.227,-0.3606,-0.397,-0.6929,0.0618,-0.3533,-1.699,0.7048,0.0971,-0.5307,1.2179,-0.5407,1.5355,0.3505,0.7263,-1.3437,2.464,0.5843,-0.0503,-0.0985,-0.1118,0.3975,-0.0495,-0.2278,-0.0697,-0.1301],"monto":12.0,"hora":12,"pais":"ES","distancia_km":80}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.es_fraude").value(false))
                .andExpect(jsonPath("$.accion").value("APROBADA"));
    }

    @Order(7)
    @Test
    @DisplayName("Fraude: pais desconocido devuelve 400 y NO envenena el circuito")
    void paisDesconocido() throws Exception {
        // El encoder de Python usa handle_unknown='ignore'. Sin el
        // rechazo explicito de PAISES_CONOCIDOS, JP pasaria al vector de
        // ceros y devolveria una prediccion sin senal del pais, con total
        // aspecto de correcta.
        mockMvc.perform(postConToken("/api/transacciones", 1L)
                        .content("""
                                {"componentes":[1.234,1.234,1.234,1.234,1.234,1.234,1.234,1.234,1.234,1.234,1.234,1.234,1.234,1.234,1.234,1.234,1.234,1.234,1.234,1.234,1.234,1.234,1.234,1.234,1.234,1.234,1.234,1.234],"monto":100,"hora":12,"pais":"ZZ","distancia_km":9000}"""))
                // 400 y no 503. La peticion es invalida, no nuestra
                // dependencia: un 503 hacia que el cliente reintentara
                // algo que jamas va a funcionar.
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("PETICION_INVALIDA"));
    }

    @Order(13)
    @Test
    @DisplayName("Un pais no soportado NO puede dejar el detector de fraude caido")
    void paisNoSoportadoNoEnvenenaElCircuito() throws Exception {
        // El fallo mas caro que encontro la revision, yendo mas alla de la
        // teoria. Un 422 de Python (pais no soportado) se traducía a
        // "IA no disponible", que era el unico tipo de retry-exceptions y
        // ademas contaba como fallo del circuito. Medido antes del arreglo,
        // contra este mismo stack:
        //
        //   ES -> 200 APROBADA
        //   AA,AB,AC,AD,AE -> 503 IA_NO_DISPONIBLE
        //   AG -> 503 CIRCUITO_ABIERTO
        //   ES -> 503 CIRCUITO_ABIERTO   <-- la peticion legitima cae
        //
        // O sea: un campo de texto libre permitia apagar la deteccion de
        // fraude para todos durante 30 s, sin necesitar credenciales
        // especiales. En pagos, es el fallo que mas caro sale.
        //
        // Aqui se ejerce el ataque y luego se comprueba que el
        // servicio legitimo sigue respondiendo.
        String cuerpo = """
                {"componentes":[-0.2743,0.1401,2.227,-0.3606,-0.397,-0.6929,0.0618,-0.3533,-1.699,0.7048,0.0971,-0.5307,1.2179,-0.5407,1.5355,0.3505,0.7263,-1.3437,2.464,0.5843,-0.0503,-0.0985,-0.1118,0.3975,-0.0495,-0.2278,-0.0697,-0.1301],"monto":12.0,"hora":12,"pais":"%s","distancia_km":80}""";

        // Formatos validos, paises que el modelo no conoce. Es lo que
        // hace un atacante: no hace falta nada raro, solo saber que hay
        // una lista.
        String[] paises = {"AA","AB","AC","AD","AE","AF","AG","AH","AI","AJ"};

        for (String pais : paises) {
            mockMvc.perform(postConToken("/api/transacciones", 1L)
                            .content(cuerpo.formatted(pais)))
                    .andExpect(status().isBadRequest());
        }

        // Si alguno hubiera envenenado el circuito, esto daria
        // CIRCUITO_ABIERTO. El @BeforeEach resetea el circuito entre
        // tests, asi que este fallo seria de este test y no heredado.
        mockMvc.perform(postConToken("/api/transacciones", 1L)
                        .content(cuerpo.formatted("ES")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accion").value("APROBADA"));

        assertThat(breakerRegistry.circuitBreaker("servicioIA").getState())
                .as("diez rechazos por contenido no abren el circuito")
                .isEqualTo(io.github.resilience4j.circuitbreaker
                        .CircuitBreaker.State.CLOSED);
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
            String respuesta = mockMvc.perform(postConToken("/api/texto", 1L)
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
    @DisplayName("Alta, login, transaccion y consulta con los tres servicios")
    void flujoCompleto() throws Exception {
        // 1. Alta de usuario (ruta publica: todavia no hay token)
        String usuarioJson = mockMvc.perform(
                        post("/api/v1/sesiones/usuarios")
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

        // 2. Login: la contrasena en claro se cambia por un token
        String token = mockMvc.perform(post("/api/v1/sesiones/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"integ@ejemplo.com",
                                 "contrasena":"contrasena-larga-123"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.tipo").value("Bearer"))
                .andExpect(jsonPath("$.usuario_id").value((int) usuarioId))
                .andReturn().getResponse().getContentAsString();

        // 3. Ese token, y solo ese, abre el historial
        String cabecera = json.readTree(token).get("token").asText();
        assertThat(cabecera.split("\\.")).as("JWT con tres partes").hasSize(3);

        // 4. Transaccion sospechosa: IA + escritura en la BD real
        mockMvc.perform(post("/api/v1/usuarios/" + usuarioId + "/transacciones")
                        .header("Authorization", "Bearer " + cabecera)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"componentes":[-9.1698,7.0922,-12.354,4.2431,-7.1764,-3.3866,-8.058,6.4429,-2.413,-6.1349,2.8267,-6.3098,-0.623,-7.2799,0.9242,-4.2155,-7.1717,-2.5503,0.5964,0.8167,0.9262,-0.8177,-0.1504,-0.0394,0.4856,-0.2643,1.1597,0.2328],"monto":99.99,"hora":3,"pais":"NG","distancia_km":5200}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accion").value("REQUIERE_REVISION"));

        // 5. Consulta: demuestra que llego a PostgreSQL de verdad
        String historial = mockMvc.perform(
                        get("/api/v1/usuarios/" + usuarioId + "/transacciones")
                        .header("Authorization", "Bearer " + cabecera))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode transacciones = json.readTree(historial);
        assertThat(transacciones).hasSize(1);
        assertThat(transacciones.get(0).get("es_fraude").asBoolean()).isTrue();
        assertThat(transacciones.get(0).get("estado").asText()).isEqualTo("ANALIZADA");
    }

    @Order(8)
    @Test
    @DisplayName("Una peticion rechazada por Python NO se persiste como si fuera limpia")
    void peticionRechazadaNoSePersisteComoLimpio() throws Exception {
        // El caso central del modulo de persistencia: una peticion que
        // acaba sin veredicto NO debe quedar guardada como "limpia".
        //
        // El camino de un pais no soportado es el que queda: Java valida
        // formato (^[A-Z]{2}$), asi que "ZZ" pasa, y es Python quien lo
        // rechaza con 422 porque no esta en la lista de paises que el
        // modelo conoce. Java lo traduce a PeticionRechazadaPorIAException
        // y responde 400.
        //
        // Lo que NO puede pasar es que eso acabe en el historico como
        // es_fraude=false. FALSE significa "evaluada y limpia"; si una
        // peticion sin evaluar se guardara asi, pasaria por aprobada en
        // cuanto la IA tuviera un mal dia.
        long usuarioId = crearUsuario("rechazada@ejemplo.com");

        String cuerpo = """
                {"componentes":[-0.2743,0.1401,2.227,-0.3606,-0.397,-0.6929,0.0618,-0.3533,-1.699,0.7048,0.0971,-0.5307,1.2179,-0.5407,1.5355,0.3505,0.7263,-1.3437,2.464,0.5843,-0.0503,-0.0985,-0.1118,0.3975,-0.0495,-0.2278,-0.0697,-0.1301],"monto":100.0,"hora":12,"pais":"ZZ","distancia_km":80}""";

        mockMvc.perform(postConToken(
                        "/api/v1/usuarios/" + usuarioId + "/transacciones", usuarioId)
                        .content(cuerpo))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("PETICION_INVALIDA"));

        // Se guarda el INTENTO, con es_fraude en NULL y estado
        // NO_ANALIZADA. Descartarlo seria peor: se perderia el registro
        // de todo lo que se intento y no se pudo evaluar, que es
        // justamente lo que despues hay que revisar a mano.
        String historial = mockMvc.perform(
                        getConToken("/api/v1/usuarios/" + usuarioId + "/transacciones",
                                usuarioId))
                .andReturn().getResponse().getContentAsString();

        JsonNode transacciones = json.readTree(historial);
        assertThat(transacciones)
                .as("el intento fallido se conserva para auditoria")
                .hasSize(1);

        JsonNode t = transacciones.get(0);
        assertThat(t.get("es_fraude").isNull())
                .as("es_fraude debe ser null, NUNCA false: false significa "
                    + "\"evaluada y limpia\"")
                .isTrue();
        assertThat(t.get("estado").asText()).isEqualTo("NO_ANALIZADA");
        assertThat(t.get("accion").isNull())
                .as("sin veredicto no puede haber accion de negocio")
                .isTrue();
    }

    // --------------------------------------- AISLAMIENTO ENTRE USUARIOS

    @Order(9)
    @Test
    @DisplayName("Dos usuarios reales: el token de uno no abre el historial del otro")
    void losTokensNoSeCruzan() throws Exception {
        // El fallo que motivo todo el cambio de seguridad, comprobado de
        // punta a punta: con la API key por cabecera, dos personas
        // compartian credencial y cada una podia leer el historial de la
        // otra. Aqui se crean dos usuarios de verdad en PostgreSQL, cada
        // uno con su token, y se comprueba que no se cruzan.
        long ana = crearUsuario("aisla-ana@ejemplo.com");
        long bruno = crearUsuario("aisla-bruno@ejemplo.com");

        // Ana analisa una transaccion.
        mockMvc.perform(postConToken("/api/v1/usuarios/" + ana + "/transacciones", ana)
                        .content("""
                                {"componentes":[-0.2743,0.1401,2.227,-0.3606,-0.397,-0.6929,0.0618,-0.3533,-1.699,0.7048,0.0971,-0.5307,1.2179,-0.5407,1.5355,0.3505,0.7263,-1.3437,2.464,0.5843,-0.0503,-0.0985,-0.1118,0.3975,-0.0495,-0.2278,-0.0697,-0.1301],"monto":12.0,"hora":12,"pais":"ES","distancia_km":80}"""))
                .andExpect(status().isOk());

        // Ana ve una transaccion en su historial.
        assertThat(json.readTree(mockMvc.perform(
                        getConToken("/api/v1/usuarios/" + ana + "/transacciones", ana))
                .andReturn().getResponse().getContentAsString()))
                .as("Ana ve lo suyo")
                .hasSize(1);

        // Bruno ve los suyos, que estan vacios: no aparece la de Ana.
        assertThat(json.readTree(mockMvc.perform(
                        getConToken("/api/v1/usuarios/" + bruno + "/transacciones", bruno))
                .andReturn().getResponse().getContentAsString()))
                .as("Bruno no ve nada de Ana")
                .isEmpty();

        // Y el token de Bruno sobre el recurso de Ana es 404, no 403: un
        // 403 confirmaria que el usuario existe.
        mockMvc.perform(getConToken("/api/v1/usuarios/" + ana + "/transacciones", bruno))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("NO_ENCONTRADO"));

        // Tampoco puede escribir en su nombre.
        mockMvc.perform(postConToken("/api/v1/usuarios/" + ana + "/transacciones", bruno)
                        .content("""
                                {"componentes":[-0.2743,0.1401,2.227,-0.3606,-0.397,-0.6929,0.0618,-0.3533,-1.699,0.7048,0.0971,-0.5307,1.2179,-0.5407,1.5355,0.3505,0.7263,-1.3437,2.464,0.5843,-0.0503,-0.0985,-0.1118,0.3975,-0.0495,-0.2278,-0.0697,-0.1301],"monto":12.0,"hora":12,"pais":"ES","distancia_km":80}"""))
                .andExpect(status().isNotFound());
    }

    @Order(10)
    @Test
    @DisplayName("Login: contrasena incorrecta 401, y el mismo mensaje que si no existiera")
    void loginFallidoNoEnumeraCuentas() throws Exception {
        crearUsuario("enumeracion@ejemplo.com");

        String cuerpoMal = mockMvc.perform(post("/api/v1/sesiones/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"enumeracion@ejemplo.com","contrasena":"incorrecta"}"""))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        String cuerpoAusente = mockMvc.perform(post("/api/v1/sesiones/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"nadie@ejemplo.com","contrasena":"lo-que-sea"}"""))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        // Si los dos cuerpos fueran distintos, un atacante sabria que
        // emails estan registrados. Y el tiempo tambien lo delata: por
        // eso UsuarioService busca siempre y verifica con BCrypt, en vez
        // de devolver antes si el email no existe.
        assertThat(json.readTree(cuerpoMal).get("mensaje").asText())
                .isEqualTo(json.readTree(cuerpoAusente).get("mensaje").asText());
    }

    @Order(11)
    @Test
    @DisplayName("Sin token, el historial devuelve 401 con cuerpo JSON")
    void sinTokenDa401ConCuerpo() throws Exception {
        long ana = crearUsuario("sin-token@ejemplo.com");

        mockMvc.perform(get("/api/v1/usuarios/" + ana + "/transacciones"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.codigo").value("NO_AUTENTICADO"));
    }

    @Order(12)
    @Test
    @DisplayName("El health sigue abierto: sin token y sin base de datos usable")
    void elHealthSigueAbierto() throws Exception {
        // El health lo consultan Docker y el balanceador, y no tienen
        // token de nadie. Cerrarlo dejaria el despliegue sin forma de
        // saber si esta sano.
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    private long crearUsuario(String email) throws Exception {
        String cuerpo = mockMvc.perform(post("/api/v1/sesiones/usuarios")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","nombre":"Test",
                                 "contrasena":"contrasena-larga-123"}""".formatted(email)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(cuerpo).get("id").asLong();
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
        mockMvc.perform(postConToken("/api/transacciones", 1L)
                        .content("""
                                {"componentes":[-0.2743,0.1401,2.227,-0.3606,-0.397,-0.6929,0.0618,-0.3533,-1.699,0.7048,0.0971,-0.5307,1.2179,-0.5407,1.5355,0.3505,0.7263,-1.3437,2.464,0.5843,-0.0503,-0.0985,-0.1118,0.3975,-0.0495,-0.2278,-0.0697,-0.1301],"monto":12.0,"hora":12,"pais":"ES","distancia_km":80}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.probabilidad").exists())
                .andExpect(jsonPath("$.modelo").exists())
                .andExpect(jsonPath("$.nivel_riesgo").exists())
                .andExpect(jsonPath("$.accion").exists())
                // El umbral viaje al cliente. Sin el, un cliente que ve
                // probabilidad 0.5 no puede saber si la decision fue
                // conservadora o agresiva.
                .andExpect(jsonPath("$.umbral").exists())
                // Y se declara que senas se usaron: el contrato incluye
                // pais y distancia, pero el modelo no los mira.
                .andExpect(jsonPath("$.senas_analizadas").value("componentes_PCA"));
    }

    @Order(4)
    @Test
    @DisplayName("La respuesta de Python trae todos los campos que Java deserializa")
    void contratoDeRespuesta() throws Exception {
        String cuerpo = mockMvc.perform(postConToken("/api/transacciones", 1L)
                        .content("""
                                {"componentes":[-9.1698,7.0922,-12.354,4.2431,-7.1764,-3.3866,-8.058,6.4429,-2.413,-6.1349,2.8267,-6.3098,-0.623,-7.2799,0.9242,-4.2155,-7.1717,-2.5503,0.5964,0.8167,0.9262,-0.8177,-0.1504,-0.0394,0.4856,-0.2643,1.1597,0.2328],"monto":99.99,"hora":3,"pais":"NG","distancia_km":5200}"""))
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
