package com.ejemplo.microservicio.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.ejemplo.microservicio.controller.AnalisisController;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Tests de la autenticacion por clave de API.
 *
 * Estos tests si usan la seguridad ACTIVADA, al contrario que los del
 * contrato HTTP. Ahi la seguridad esta apagada y se comprueba que el
 * endpoint responde; aqui esta encendida y se comprueba que cierra.
 *
 * Un detalle que importa: se prueba el filtro de verdad, no una simulacion.
 * Un filtro que devuelve 200 cuando deberia devolver 401 es el fallo
 * tipico, y solo se detecta ejercendolo.
 */
// Se acota a AnalisisController a proposito. Un @WebMvcTest sin
// `controllers` carga TODOS los controladores, y UsuarioController pide
// UsuarioService y TransaccionService, que no existen en un slice web.
// Estos tests van de la autenticacion, no de cada endpoint.
@WebMvcTest(controllers = AnalisisController.class)
@Import(ConfiguracionSeguridad.class)
class SeguridadApiKeyTest {

    private static final String CLAVE = "clave-de-prueba-larga-suficiente-1234567890";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private com.ejemplo.microservicio.service.AnalisisService analisisService;

    @MockBean
    private ValidadorApiKey validadorMock;

    @Nested
    @DisplayName("Con la seguridad activada")
    class Activada {

        @Test
        @DisplayName("Sin cabecera devuelve 401 y no ejecuta el endpoint")
        void sinCabeceraDa401() throws Exception {
            org.mockito.Mockito.when(validadorMock.estaActivada()).thenReturn(true);
            org.mockito.Mockito.when(validadorMock.esValida(null)).thenReturn(false);

            mockMvc.perform(post("/api/transacciones")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"monto":100,"hora":12,"pais":"ES","distancia_km":50}"""))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.codigo").value("NO_AUTENTICADO"));

            // Lo importante: el controlador NO debe llegar a ejecutarse.
            org.mockito.Mockito.verifyNoInteractions(analisisService);
        }

        @Test
        @DisplayName("Clave incorrecta devuelve 401")
        void claveIncorrectaDa401() throws Exception {
            org.mockito.Mockito.when(validadorMock.estaActivada()).thenReturn(true);
            org.mockito.Mockito.when(validadorMock.esValida("clave-mala"))
                    .thenReturn(false);

            mockMvc.perform(post("/api/transacciones")
                            .header("X-API-Key", "clave-mala")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"monto":100,"hora":12,"pais":"ES","distancia_km":50}"""))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("El 401 no dice SI FALLO LA CLAVE o SI FALTO LA CABECERA")
        void el401NoRevelaElMotivo() throws Exception {
            org.mockito.Mockito.when(validadorMock.estaActivada()).thenReturn(true);
            org.mockito.Mockito.when(validadorMock.esValida(org.mockito.ArgumentMatchers.any()))
                    .thenReturn(false);

            String cuerpo = mockMvc.perform(post("/api/transacciones")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andReturn().getResponse().getContentAsString();

            // Distinguir "falta la cabecera" de "la clave es incorrecta"
            // ayuda al atacante a saber si va por buen camino. El mismo
            // mensaje para los dos casos lo evita.
            assertThat(cuerpo)
                    .doesNotContain("incorrecta")
                    .doesNotContain("incorrecto")
                    .doesNotContain("falta")
                    .doesNotContain("ausente")
                    .contains("NO_AUTENTICADO");
        }

        @Test
        @DisplayName("Con la clave correcta, la peticion pasa")
        void claveCorrectaPasa() throws Exception {
            org.mockito.Mockito.when(validadorMock.estaActivada()).thenReturn(true);
            org.mockito.Mockito.when(validadorMock.esValida(CLAVE)).thenReturn(true);

            org.mockito.Mockito.when(analisisService.analizarTransaccion(
                            org.mockito.ArgumentMatchers.any()))
                    .thenReturn(new com.ejemplo.microservicio.dto.RespuestaFraude(
                            false, 0.01, "bajo", "rf", "APROBADA", 5L));

            mockMvc.perform(post("/api/transacciones")
                            .header("X-API-Key", CLAVE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"monto":100,"hora":12,"pais":"ES","distancia_km":50}"""))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("El health check sigue abierto sin clave")
        void healthSigueAbierto() throws Exception {
            org.mockito.Mockito.when(validadorMock.estaActivada()).thenReturn(true);
            org.mockito.Mockito.when(validadorMock.esValida(org.mockito.ArgumentMatchers.any()))
                    .thenReturn(false);

            // /actuator/health debe quedar accesible sin clave: Docker,
            // Kubernetes y el balanceador lo consultan para decidir si
            // mandan trafico, y ninguno tiene la clave de la aplicacion.
            // En este slice de test no hay actuator en el contexto, asi
            // que se comprueba el filtro de forma directa: shouldNotFilter
            // debe ser true para la ruta del health.
            var peticion = new org.springframework.mock.web.MockHttpServletRequest(
                    "GET", "/actuator/health");
            org.assertj.core.api.Assertions.assertThat(
                    new FiltroApiKey(validadorMock).shouldNotFilter(peticion))
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("Con la seguridad desactivada")
    class Desactivada {

        @Test
        @DisplayName("Una clave cualquiera no bloquea nada")
        void noBloquea() throws Exception {
            org.mockito.Mockito.when(validadorMock.estaActivada()).thenReturn(false);

            org.mockito.Mockito.when(analisisService.analizarSentimiento(
                            org.mockito.ArgumentMatchers.any()))
                    .thenReturn(new com.ejemplo.microservicio.dto.RespuestaSentimiento(
                            "positivo", 0.9, java.util.Map.of("positivo", 0.9), "tfidf"));

            mockMvc.perform(post("/api/texto")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"texto":"excelente"}"""))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("El validador de claves")
    class Validador {

        private PropiedadesSeguridad props(boolean activada, String clave) {
            return new PropiedadesSeguridad(activada, clave, null);
        }

        @Test
        @DisplayName("Acepta la clave exacta y rechaza el resto")
        void comparaExactamente() {
            var v = new ValidadorApiKey(props(true, "secreto123"));

            assertThat(v.esValida("secreto123")).isTrue();
            assertThat(v.esValida("secreto124")).isFalse();
            assertThat(v.esValida("secreto")).isFalse();
            assertThat(v.esValida("secreto1234")).isFalse();
            assertThat(v.esValida("SECRETO123")).isFalse();
        }

        @Test
        @DisplayName("Rechaza null y cadena vacia sin lanzar excepcion")
        void rechazaVacios() {
            var v = new ValidadorApiKey(props(true, "secreto123"));

            // Sin esta comprobacion, un cliente que no envia la cabecera
            // provocaria NullPointerException en el filtro y devolveria un
            // 500 en lugar de un 401.
            assertThat(v.esValida(null)).isFalse();
            assertThat(v.esValida("")).isFalse();
        }

        @Test
        @DisplayName("Sin clave configurada, nada es valido")
        void sinClaveConfigurada() {
            var v = new ValidadorApiKey(props(true, ""));

            // Falla cerrado. Una API con seguridad activada pero sin clave
            // configurada esta ABIERTA si se usara equals() con vacio.
            assertThat(v.esValida("")).isFalse();
            assertThat(v.esValida("cualquier")).isFalse();
        }
    }
}