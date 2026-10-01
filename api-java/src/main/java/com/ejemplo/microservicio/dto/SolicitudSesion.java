package com.ejemplo.microservicio.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Credenciales de inicio de sesion.
 *
 * El email se normaliza a minusculas antes de buscar: sin eso, "Ana@x.com"
 * y "ana@x.com" seriam dos cuentas distintas, y el usuario que escribe su
 * email con otra caja no podria entrar nunca.
 */
public record SolicitudSesion(

        @NotBlank(message = "el email es obligatorio")
        @Email(message = "el email no tiene formato valido")
        String email,

        @NotBlank(message = "la contrasena es obligatoria")
        String contrasena
) {

    public SolicitudSesion {
        if (email != null) {
            email = email.trim().toLowerCase(java.util.Locale.ROOT);
        }
    }
}
