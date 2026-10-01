package com.ejemplo.microservicio.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Token de sesion emitido tras un login correcto.
 *
 * El tipo va aparte del token: un cliente que no distingue "Bearer" de
 * otro esquema acaba mandando la cabecera mal y recibe 401 sin entender
 * por que.
 */
public record RespuestaToken(

        @JsonProperty("token")
        String token,

        @JsonProperty("tipo")
        String tipo,

        @JsonProperty("usuario_id")
        Long usuarioId
) {

    /** Cabecera completa que debe enviar el cliente. */
    public String cabecera() {
        return tipo + " " + token;
    }
}
