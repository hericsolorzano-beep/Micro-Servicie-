package com.ejemplo.microservicio.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/** Respuesta crudo del servicio de IA para sentimiento. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RespuestaSentimientoPython(

    @JsonProperty("sentimiento")
    String sentimiento,

    @JsonProperty("confianza")
    Double confianza,

    @JsonProperty("probabilidades")
    Map<String, Double> probabilidades,

    @JsonProperty("modelo")
    String modelo
) {
}