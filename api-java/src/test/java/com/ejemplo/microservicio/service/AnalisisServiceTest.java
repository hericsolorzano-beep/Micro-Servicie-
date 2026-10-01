package com.ejemplo.microservicio.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.ejemplo.microservicio.dto.PeticionFraudePython;
import com.ejemplo.microservicio.dto.PeticionTextoPython;
import com.ejemplo.microservicio.dto.RespuestaFraude;
import com.ejemplo.microservicio.dto.RespuestaFraudePython;
import com.ejemplo.microservicio.dto.RespuestaSentimientoPython;
import com.ejemplo.microservicio.dto.SolicitudTexto;
import com.ejemplo.microservicio.dto.SolicitudTransaccion;
import com.ejemplo.microservicio.exception.ServicioIANoDisponibleException;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tests de la logica de negocio con el cliente de IA simulado.
 *
 * Por que un doble y no el Python real: estos tests verifican NUESTRA
 * logica (que accion corresponde a cada nivel de riesgo), no la del modelo.
 * Meter scikit-learn aqui haria los tests lentos, no deterministas y los
 * ataria a los datos de entrenamiento. Los tests de integracion con el
 * servicio real se hacen aparte, en un perfil distinto.
 */
@ExtendWith(MockitoExtension.class)
class AnalisisServiceTest {

    // Se simula la capa RESILIENTE, no el cliente HTTP: AnalisisService
    // consume ClienteIAResiliente, y por eso es el que hay que doblar.
    @Mock
    private ClienteIAResiliente clienteIA;

    @InjectMocks
    private AnalisisService servicio;

    private static SolicitudTransaccion transaccion() {
        return new SolicitudTransaccion(9500.0, 3, "NG", 5200.0);
    }

    @Test
    @DisplayName("Nivel critico bloquea la transaccion")
    void nivelCriticoBloquea() throws Exception {
        when(clienteIA.predecirFraude(any(PeticionFraudePython.class)))
                .thenReturn(new RespuestaFraudePython(true, 0.95, "critico", "rf"));

        RespuestaFraude r = servicio.analizarTransaccion(transaccion());

        assertTrue(r.esFraude());
        assertEquals("BLOQUEADA", r.accion());
    }

    @Test
    @DisplayName("Nivel alto requiere revision humana")
    void nivelAltoRequiereRevision() throws Exception {
        when(clienteIA.predecirFraude(any(PeticionFraudePython.class)))
                .thenReturn(new RespuestaFraudePython(true, 0.70, "alto", "rf"));

        assertEquals("REQUIERE_REVISION",
                servicio.analizarTransaccion(transaccion()).accion());
    }

    @Test
    @DisplayName("Nivel medio solo se monitoriza")
    void nivelMedioSeMonitoriza() throws Exception {
        when(clienteIA.predecirFraude(any(PeticionFraudePython.class)))
                .thenReturn(new RespuestaFraudePython(true, 0.30, "medio", "rf"));

        assertEquals("MONITORIZAR",
                servicio.analizarTransaccion(transaccion()).accion());
    }

    @Test
    @DisplayName("Nivel desconocido escala a revision, nunca aprueba")
    void nivelDesconocidoEscala() throws Exception {
        // Si Python anade un nivel nuevo o escribe mal uno, aprobar una
        // transaccion marcada como fraude es el error que no se deshace.
        when(clienteIA.predecirFraude(any(PeticionFraudePython.class)))
                .thenReturn(new RespuestaFraudePython(true, 0.60, "desconocido", "rf"));

        assertEquals("REQUIERE_REVISION",
                servicio.analizarTransaccion(transaccion()).accion());
    }

    @Test
    @DisplayName("Sin fraude se aprueba")
    void sinFraudeSeAprueba() throws Exception {
        when(clienteIA.predecirFraude(any(PeticionFraudePython.class)))
                .thenReturn(new RespuestaFraudePython(false, 0.02, "bajo", "rf"));

        RespuestaFraude r = servicio.analizarTransaccion(transaccion());

        assertEquals("APROBADA", r.accion());
        assertTrue(r.tiempoInferenciaMs() >= 0);
    }

    @Test
    @DisplayName("La caida de la IA se propaga como excepcion dechecked")
    void caidaDeIaSePropaga() throws Exception {
        when(clienteIA.predecirFraude(any(PeticionFraudePython.class)))
                .thenThrow(new ServicioIANoDisponibleException("IA caida"));

        // El service NO inventa un resultado. Prefiere fallar a devolver
        // un "no es fraude" fabricated, porque eso supondria autorizar
        // transacciones en un sistema que no puede evaluar el riesgo.
        assertThrows(ServicioIANoDisponibleException.class,
                () -> servicio.analizarTransaccion(transaccion()));
    }

    @Test
    @DisplayName("El texto viaja intacto al servicio de IA")
    void textoViajaIntacto() throws Exception {
        String texto = "Pésima calidad, llegó roto";
        when(clienteIA.predecirSentimiento(any(PeticionTextoPython.class)))
                .thenReturn(new RespuestaSentimientoPython(
                        "negativo", 0.96, Map.of("negativo", 0.96), "tfidf"));

        var r = servicio.analizarSentimiento(new SolicitudTexto(texto));

        assertEquals("negativo", r.sentimiento());
        assertEquals(0.96, r.confianza());
    }
}