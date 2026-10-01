package com.ejemplo.microservicio.config;

/**
 * Si la clave de firma de JWT vino de la configuracion o se genero al
 * arrancar.
 *
 * Existe por el mismo motivo que existia el aviso de "seguridad
 * desactivada": el olvido de un secreto no falla, y produce un
 * despliegue que parece correcto y no lo es. Con la API key era
 * "seguridad desactivada por defecto"; con JWT es "clave distinta en cada
 * arranque", que es igual de peligroso y mas dificil de ver: la API
 * responde 200, solo que todas las sesiones mueren en cada reinicio.
 *
 * Publicarlo en el health check convierte el olvido en algo visible.
 *
 * @param definida true si JWT_SECRET venia de la configuracion
 * @param origen valor sin secreto, solo para diagnostico
 */
public record EstadoClaveJwt(boolean definida, String origen) {

    public static EstadoClaveJwt deConfiguracion() {
        return new EstadoClaveJwt(true, "JWT_SECRET");
    }

    public static EstadoClaveJwt generada() {
        return new EstadoClaveJwt(false, "aleatoria-en-el-arranque");
    }
}
