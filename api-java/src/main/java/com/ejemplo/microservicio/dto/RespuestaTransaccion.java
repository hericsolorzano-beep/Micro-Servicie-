package com.ejemplo.microservicio.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Transaccion persistida, tal como se devuelve al cliente.
 *
 * El campo analizado es lo que mas importa del contrato. Distingue tres
 * estados que una respuesta ingenua colapsaria en uno:
 *
 *   es_fraude = false  -> se evaluo y salio limpia
 *   es_fraude = true   -> se evaluo y salio sospechosa
 *   es_fraude = null   -> NO se pudo evaluar (la IA no respondio)
 *
 * Sin ese campo, un cliente que solo mire es_fraude con un if (booleano)
 * trataria null como false y daria por buena una transaccion que nadie
 * miro. Por eso se devuelve ademas el estado como texto explicito.
 */
public record RespuestaTransaccion(

        @JsonProperty("id")
        Long id,

        @JsonProperty("usuario_id")
        Long usuarioId,

        @JsonProperty("monto")
        BigDecimal monto,

        @JsonProperty("hora")
        Integer hora,

        @JsonProperty("pais")
        String pais,

        @JsonProperty("distancia_km")
        BigDecimal distanciaKm,

        @JsonProperty("es_fraude")
        Boolean esFraude,

        @JsonProperty("probabilidad")
        BigDecimal probabilidad,

        @JsonProperty("nivel_riesgo")
        String nivelRiesgo,

        @JsonProperty("modelo")
        String modelo,

        @JsonProperty("accion")
        String accion,

        @JsonProperty("error_analisis")
        String errorAnalisis,

        /** "ANALIZADA", "NO_ANALIZADA" o "ANALIZANDO". */
        @JsonProperty("estado")
        String estado,

        @JsonProperty("tiempo_inferencia_ms")
        Long tiempoInferenciaMs,

        @JsonProperty("creado_en")
        Instant creadoEn
) {

    public static RespuestaTransaccion desde(
            com.ejemplo.microservicio.domain.Transaccion t) {

        // switch(true) con "case condicion" no compila: las etiquetas de un
        // switch deben ser constantes. El orden importa: primero se
        // pregunta por el error (si la IA fallo, el estado es
        // NO_ANALIZADA aunque es_fraude venga nulo).
        String estado;
        if (t.getErrorAnalisis() != null) {
            estado = "NO_ANALIZADA";
        } else if (t.fueAnalizada()) {
            estado = "ANALIZADA";
        } else {
            estado = "ANALIZANDO";
        }

        return new RespuestaTransaccion(
                t.getId(),
                t.getUsuario().getId(),
                t.getMonto(),
                t.getHora(),
                t.getPais(),
                t.getDistanciaKm(),
                t.getEsFraude(),
                t.getProbabilidad(),
                t.getNivelRiesgo(),
                t.getModelo(),
                t.getAccion(),
                t.getErrorAnalisis(),
                estado,
                t.getTiempoInferenciaMs(),
                t.getCreadoEn());
    }
}