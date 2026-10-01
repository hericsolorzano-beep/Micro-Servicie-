package com.ejemplo.microservicio.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Contrato publico para el analisis de sentimiento. */
public record SolicitudTexto(

    @NotBlank(message = "el texto es obligatorio")
    @Size(min = 1, max = 5000, message = "el texto debe tener entre 1 y 5000 caracteres")
    String texto
) {
}