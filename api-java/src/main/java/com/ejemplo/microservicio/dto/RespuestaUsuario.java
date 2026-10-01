package com.ejemplo.microservicio.dto;

import com.ejemplo.microservicio.domain.Usuario;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Respuesta de alta y consulta de usuario.
 *
 * Fijar el contrato en snake_case, igual que el resto de la API.
 *
 * NO tiene ningun campo de contrasena, ni siquiera el hash. Esa ausencia
 * es deliberada: un DTO de salida que incluyera el hash convertiria
 * cualquier forgot-password en una fuga de credenciales. Los
 * getters de Usuario tampoco exponen el hash por el mismo motivo.
 */
public record RespuestaUsuario(

        @JsonProperty("id")
        Long id,

        @JsonProperty("email")
        String email,

        @JsonProperty("nombre")
        String nombre,

        @JsonProperty("activo")
        Boolean activo,

        @JsonProperty("creado_en")
        java.time.Instant creadoEn
) {

    public static RespuestaUsuario desde(Usuario usuario) {
        return new RespuestaUsuario(
                usuario.getId(),
                usuario.getEmail(),
                usuario.getNombre(),
                usuario.isActivo(),
                usuario.getCreadoEn());
    }
}