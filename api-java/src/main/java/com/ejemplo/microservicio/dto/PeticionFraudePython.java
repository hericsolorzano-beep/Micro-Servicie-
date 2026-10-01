package com.ejemplo.microservicio.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Contrato PRIVADO hacia el servicio de IA.
 *
 * Separate del DTO publico a proposito. Si el dia de manana Python exige un
 * campo "dispositivo_id", este record cambia y el contrato publico no se
 * entera. Esa es toda la gracia de la frontera.
 */
public record PeticionFraudePython(

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