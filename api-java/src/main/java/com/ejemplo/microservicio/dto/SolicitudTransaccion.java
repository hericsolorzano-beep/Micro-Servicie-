package com.ejemplo.microservicio.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Contrato PUBLICO de la API. Es lo que ve el cliente externo.
 *
 * Este tipo NO es el que viaja a Python. Es un DTO distinto, separado a
 * proposito (ver PeticionFraudePython). Mezclarlos obliga a que cualquier
 * cambio interno del servicio de IA se convierta en un cambio
 * incompatible para nuestros clientes.
 */
public record SolicitudTransaccion(

    @NotNull(message = "el monto es obligatorio")
    @DecimalMin(value = "0.01", message = "el monto debe ser mayor que cero")
    @DecimalMax(value = "1000000.00", message = "el monto excede el limite permitido")
    Double monto,

    @NotNull(message = "la hora es obligatoria")
    @Min(value = 0, message = "la hora debe estar entre 0 y 23")
    @Max(value = 23, message = "la hora debe estar entre 0 y 23")
    Integer hora,

    @NotBlank(message = "el pais es obligatorio")
    @Pattern(
        regexp = "^[A-Z]{2}$",
        message = "el pais debe ser un codigo ISO de 2 letras en mayusculas")
    @Size(min = 2, max = 2, message = "el pais debe tener exactamente 2 caracteres")
    String pais,

    // snake_case explicito: sin esta anotacion Jackson busca "distanciaKm"
    // y el campo del JSON ("distancia_km") llega a null, lo que dispara la
    // validacion con un 400 confuso en vez de un error de mapeo claro.
    @JsonProperty("distancia_km")
    @NotNull(message = "la distancia es obligatoria")
    @DecimalMin(value = "0.0", message = "la distancia no puede ser negativa")
    @DecimalMax(value = "20000.0", message = "la distancia excede el rango admitido")
    Double distanciaKm
) {
}