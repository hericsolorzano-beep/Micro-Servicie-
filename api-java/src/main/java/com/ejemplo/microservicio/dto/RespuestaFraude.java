package com.ejemplo.microservicio.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Respuesta PUBLICA de Spring hacia el cliente externo.
 *
 * Las anotaciones @JsonProperty fijan el contrato en snake_case. Es una
 * decision, no un descuido: un cliente que se integra con otros servicios
 * no deberia tener que aprender la convencion de nombres de Java.
 *
 * Este record NUNCA lo deserializa Python: viaja en un solo sentido,
 * de Spring hacia el cliente externo.
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

    /** Metadatos que Spring anade: Python no los manda, el cliente los espera. */
    @JsonProperty("accion")
    String accion,

    @JsonProperty("tiempo_inferencia_ms")
    Long tiempoInferenciaMs
) {
}