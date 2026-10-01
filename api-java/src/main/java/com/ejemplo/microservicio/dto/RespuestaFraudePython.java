package com.ejemplo.microservicio.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Respuesta crudo del servicio de IA, tal cual llega.
 *
 * @JsonIgnoreProperties(ignoreUnknown = true) es la red de seguridad: si
 * Python anade un campo nuevo, Spring lo ignora en vez de lanzar
 * UnrecognizedPropertyException y romper el endpoint entero. Un despliegue
 * de Python no debe ser una caida de produccion en Java.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RespuestaFraudePython(

    @JsonProperty("es_fraude")
    Boolean esFraude,

    @JsonProperty("probabilidad")
    Double probabilidad,

    @JsonProperty("nivel_riesgo")
    String nivelRiesgo,

    @JsonProperty("modelo")
    String modelo
) {
}