package com.ejemplo.microservicio.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/** Respuesta publica del analisis de sentimiento. */
public record RespuestaSentimiento(

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