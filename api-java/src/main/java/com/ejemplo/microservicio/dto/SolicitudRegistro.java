package com.ejemplo.microservicio.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Alta de usuario.
 *
 * El campo se llama contrasena y viaja solo en la entrada. La respuesta
 * NUNCA lo devuelve: Usuario no tiene getter para la contrasena en claro
 * porque no la guarda, y el DTO de salida no tiene ese campo.
 */
public record SolicitudRegistro(

        @NotNull(message = "el email es obligatorio")
        @Email(message = "el email no tiene formato valido")
        @NotBlank(message = "el email es obligatorio")
        String email,

        @NotNull(message = "el nombre es obligatorio")
        @NotBlank(message = "el nombre es obligatorio")
        @Size(max = 100, message = "el nombre es demasiado largo")
        String nombre,

        @NotNull(message = "la contrasena es obligatoria")
        @NotBlank(message = "la contrasena es obligatoria")
        @Size(
                min = 8, max = 100,
                message = "la contrasena debe tener entre 8 y 100 caracteres")
        String contrasena
) {
}