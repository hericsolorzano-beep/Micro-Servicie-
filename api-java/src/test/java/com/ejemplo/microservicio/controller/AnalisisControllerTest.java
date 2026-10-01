package com.ejemplo.microservicio.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ejemplo.microservicio.client.ClienteIAService;
import com.ejemplo.microservicio.dto.PeticionFraudePython;
import com.ejemplo.microservicio.dto.RespuestaFraudePython;
import com.ejemplo.microservicio.dto.SolicitudTexto;
import com.ejemplo.microservicio.dto.SolicitudTransaccion;
import com.ejemplo.microservicio.exception.ServicioIANoDisponibleException;
import com.ejemplo.microservicio.security.ConfiguracionSeguridad;
import com.ejemplo.microservicio.security.ValidadorApiKey;
import com.ejemplo.microservicio.service.AnalisisService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Tests del contrato HTTP publico.
 *
 * @WebMvcTest carga solo la capa web, no toda la aplicacion: por eso es
 * rapido y por eso hay que declarar los beans que faltan con @MockBean.
 *
 * Estos tests fijan el CONTRATO PUBLICO. Si alguien renombra un campo de
 * la respuesta, el test falla a proposito: ese cambio seria incompatible
 * para los clientes que ya consumen esta API.
 */
@WebMvcTest(controllers = AnalisisController.class)
@Import(ConfiguracionSeguridad.class)
class AnalisisControllerTest {

    // @WebMvcTest solo carga los beans de la capa web. FiltroApiKey es un
    // filtro de servlet y SI entra, pero su dependencia ValidadorApiKey no.
    // Sin declararla aqui el contexto no arranca, y los 10 tests fallan con
    // un error que no tiene nada que ver con lo que se quiere probar.
    @MockitoBean
    private ValidadorApiKey validadorApiKey;

    /**
     * Estos tests cubren el contrato HTTP, no la seguridad. La seguridad
     * viene desactivada por defecto (es lo que permite curl en local), asi
     * que el filtro deja pasar y estos tests ven lo que el endpoint
     * responde de verdad. La seguridad tiene su propia clase de test.
     *
     * Sin esto, un mock devuelve false por defecto, el filtro rechaza
     * cada peticion con 401 y los 10 tests fallarian por un motivo que no
     * es el que persiguen.
     */
    @BeforeEach
    void desactivarSeguridad() {
        org.mockito.Mockito.when(validadorApiKey.estaActivada()).thenReturn(false);
    }

    @Autowired
    private MockMvc mockMvc;

    // @MockitoBean sustituye al @MockBean deprecado en Spring Boot 3.4+.
    // Mismo efecto, pero sin el aviso de eliminacion.
    @MockitoBean
    private AnalisisService analisisService;

    @Test
    @DisplayName("Transaccion valida devuelve 200 con snake_case")
    void transaccionValida() throws Exception {
        org.mockito.Mockito.when(analisisService.analizarTransaccion(
                        org.mockito.ArgumentMatchers.any(SolicitudTransaccion.class)))
                .thenReturn(new com.ejemplo.microservicio.dto.RespuestaFraude(
                        true, 0.93, "critico", "rf", "BLOQUEADA", 12L));

        mockMvc.perform(post("/api/transacciones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"monto":9500,"hora":3,"pais":"NG","distancia_km":5200}"""))
                .andExpect(status().isOk())
                // snake_case exacto: es lo que consume el cliente externo.
                .andExpect(jsonPath("$.es_fraude").value(true))
                .andExpect(jsonPath("$.nivel_riesgo").value("critico"))
                .andExpect(jsonPath("$.accion").value("BLOQUEADA"))
                .andExpect(jsonPath("$.probabilidad").value(0.93));
    }

    @Test
    @DisplayName("Monto negativo devuelve 400 con detalle por campo")
    void montoNegativoDa400() throws Exception {
        mockMvc.perform(post("/api/transacciones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"monto":-50,"hora":3,"pais":"NG","distancia_km":5200}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("VALIDACION"))
                .andExpect(jsonPath("$.detalles.monto").exists());
    }

    @Test
    @DisplayName("Pais en minusculas se rechaza por formato")
    void paisInvalidoDa400() throws Exception {
        mockMvc.perform(post("/api/transacciones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"monto":100,"hora":3,"pais":"ng","distancia_km":50}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detalles.pais").exists());
    }

    @Test
    @DisplayName("IA caida devuelve 503, no 500")
    void iaCaidaDa503() throws Exception {
        org.mockito.Mockito.when(analisisService.analizarTransaccion(
                        org.mockito.ArgumentMatchers.any(SolicitudTransaccion.class)))
                .thenThrow(new ServicioIANoDisponibleException("IA caida"));

        mockMvc.perform(post("/api/transacciones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"monto":100,"hora":3,"pais":"ES","distancia_km":50}"""))
                // 503 y no 500: el problema es nuestra dependencia, no la
                // peticion. El cliente debe poder distinguir ambos casos.
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.codigo").value("IA_NO_DISPONIBLE"));
    }

    @Test
    @DisplayName("Texto valido devuelve el sentimiento con sus probabilidades")
    void textoValido() throws Exception {
        org.mockito.Mockito.when(analisisService.analizarSentimiento(
                        org.mockito.ArgumentMatchers.any(SolicitudTexto.class)))
                .thenReturn(new com.ejemplo.microservicio.dto.RespuestaSentimiento(
                        "negativo", 0.96,
                        java.util.Map.of("negativo", 0.96, "positivo", 0.03),
                        "tfidf"));

        mockMvc.perform(post("/api/texto")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"texto":"Pésima calidad, llegó roto"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sentimiento").value("negativo"))
                .andExpect(jsonPath("$.probabilidades.negativo").value(0.96));
    }

    @Test
    @DisplayName("JSON mal formado devuelve 400, no 500")
    void jsonMalFormadoDa400() throws Exception {
        // Sin el handler explicito de HttpMessageNotReadableException, el
        // catch-all de Exception lo convierte en 500 y el cliente no puede
        // distinguir "tu JSON esta roto" de "tenemos un fallo".
        mockMvc.perform(post("/api/transacciones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"monto\": 100, \"hora\":}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("VALIDACION"));
    }

    @Test
    @DisplayName("Ruta inexistente devuelve 404, no 500")
    void rutaInexistenteDa404() throws Exception {
        mockMvc.perform(post("/api/no-existe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET en un endpoint de POST devuelve 405, no 500")
    void metodoIncorrectoDa405() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/transacciones"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    @DisplayName("Content-Type incorrecto devuelve 415 con codigo propio")
    void tipoDeContenidoInvalidoDa415() throws Exception {
        mockMvc.perform(post("/api/transacciones")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("monto=100"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.codigo").value("TIPO_NO_SOPORTADO"));
    }

    @Test
    @DisplayName("Circuito abierto devuelve 503 con su propio codigo")
    void circuitoAbiertoDa503() throws Exception {
        // Sin este handler, CallNotPermittedException cae en el catch-all y
        // el cliente ve un 500, que dice "fallo de codigo" cuando lo que
        // ocurre es una proteccion funcionando.
        org.mockito.Mockito.when(analisisService.analizarTransaccion(
                        org.mockito.ArgumentMatchers.any(SolicitudTransaccion.class)))
                .thenThrow(io.github.resilience4j.circuitbreaker
                        .CallNotPermittedException.createCallNotPermittedException(
                                io.github.resilience4j.circuitbreaker
                                        .CircuitBreaker.ofDefaults("servicioIA")));

        mockMvc.perform(post("/api/transacciones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"monto":9000,"hora":3,"pais":"NG","distancia_km":5200}"""))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.codigo").value("CIRCUITO_ABIERTO"));
    }

    @Test
    @DisplayName("Bulkhead lleno devuelve 503 con su propio codigo")
    void bulkheadLlenoDa503() throws Exception {
        org.mockito.Mockito.when(analisisService.analizarTransaccion(
                        org.mockito.ArgumentMatchers.any(SolicitudTransaccion.class)))
                .thenThrow(io.github.resilience4j.bulkhead.BulkheadFullException
                        .createBulkheadFullException(
                                io.github.resilience4j.bulkhead
                                        .Bulkhead.ofDefaults("servicioIA")));

        mockMvc.perform(post("/api/transacciones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"monto":100,"hora":3,"pais":"ES","distancia_km":50}"""))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.codigo").value("SERVICIO_SATURADO"));
    }

    @Test
    @DisplayName("Texto vacio devuelve 400")
    void textoVacioDa400() throws Exception {
        mockMvc.perform(post("/api/texto")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"texto":""}"""))
                .andExpect(status().isBadRequest());
    }
}