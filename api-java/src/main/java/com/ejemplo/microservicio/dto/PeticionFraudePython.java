package com.ejemplo.microservicio.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Contrato PRIVADO hacia el servicio de IA.
 *
 * Separate del DTO publico a proposito. Si manana el servicio de IA pide
 * otro formato, este record cambia y el contrato publico no se entera.
 */
public record PeticionFraudePython(

        @JsonProperty("componentes")
        List<Double> componentes,

        @JsonProperty("monto")
        Double monto,

        @JsonProperty("hora")
        Integer hora,

        @JsonProperty("pais")
        String pais,

        @JsonProperty("distancia_km")
        Double distanciaKm
) {
}