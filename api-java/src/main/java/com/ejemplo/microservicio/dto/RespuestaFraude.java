package com.ejemplo.microservicio.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Respuesta PUBLICA de Spring hacia el cliente externo.
 *
 * Incluye los datos que el modelo NO usa (pais, distancia) para que el
 * cliente pueda mostrar el contexto de la operacion, y anade
 * "senas_analizadas" para que nadie asuma que esos campos han Carry la
 * deteccion.
 */
public record RespuestaFraude(

        @JsonProperty("es_fraude")
        Boolean esFraude,

        @JsonProperty("probabilidad")
        Double probabilidad,

        @JsonProperty("nivel_riesgo")
        String nivelRiesgo,

        @JsonProperty("modelo")
        String modelo,

        @JsonProperty("accion")
        String accion,

        @JsonProperty("tiempo_inferencia_ms")
        Long tiempoInferenciaMs,

        /**
         * Umbral con el que se decidio el veredicto. Lo devuelve el
         * servicio de IA, no se recalcula aqui.
         */
        @JsonProperty("umbral")
        Double umbral,

        /**
         * Aclaracion explicita, porque el contrato incluye pais y distancia
         * pero el modelo se entreno con datos que no los tienen.
         */
        @JsonProperty("senas_analizadas")
        String senasAnalizadas
) {

    public static final String SENAS = "componentes_PCA";

    public static RespuestaFraude de(RespuestaFraudePython r, String accion, long ms) {
        return new RespuestaFraude(
                r.esFraude(),
                r.probabilidad(),
                r.nivelRiesgo(),
                r.modelo(),
                accion,
                ms,
                r.umbral(),
                SENAS);
    }
}