package com.ejemplo.microservicio.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Contrato privado para enviar texto al servicio de IA. */
public record PeticionTextoPython(@JsonProperty("texto") String texto) {
}