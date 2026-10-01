package com.ejemplo.microservicio.security;

import org.springframework.stereotype.Component;

/**
 * Compara la clave que trae la peticion con la del servidor.
 *
 * Comparacion en tiempo constante con MessageDigest.isEqual, y no con
 * String.equals. La razon no es paranoidica: equals() devuelve false en
 * cuanto encuentra el primer caracter distinto, así que el tiempo que
 * tarda revela cuántos caracteres correctos lleva el atacante. Repetiendo
 * peticiones y midiendo, se puede reconstruir la clave byte a byte. Es un
 * ataque real y documentado contra comparaciones ingenuas.
 *
 * isEqual() siempre recorre toda la entrada, así que el tiempo no depende
 * de dónde esté la diferencia.
 */
@Component
public class ValidadorApiKey {

    private final byte[] claveEsperada;
    private final boolean requerida;

    public ValidadorApiKey(PropiedadesSeguridad props) {
        this.claveEsperada = props.clave().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        this.requerida = props.activada();
    }

    /**
     * @param claveRecibida la cabecera X-API-Key, o null si no vino
     */
    public boolean esValida(String claveRecibida) {
        if (claveRecibida == null || claveRecibida.isEmpty()) {
            return false;
        }
        byte[] recibida = claveRecibida.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return java.security.MessageDigest.isEqual(claveEsperada, recibida);
    }

    public boolean estaActivada() {
        return requerida;
    }

    /**
     * True si hay una clave configurada con la que comparar.
     *
     * Si se activa la seguridad sin clave, esto devuelve false y el filtro
     * rechaza todo: falla cerrado. Lo contrario (aceptar una clave vacia
     * porque el cliente no mando ninguna) abriria la API por un descuido
     * de despliegue.
     */
    public boolean isValida() {
        return claveEsperada.length > 0;
    }
}