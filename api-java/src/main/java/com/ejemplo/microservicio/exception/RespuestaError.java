package com.ejemplo.microservicio.exception;

/**
 * Respuesta estandar de error de la API publica.
 *
 * Formato uniforme: el cliente siempre recibe {codigo, mensaje, detalles}
 * y nunca una traza de Java ni un HTML de Whitelabel. La forma importa mas
 * que el mensaje: permite al cliente reaccionar de forma programatica.
 */
public record RespuestaError(
    String codigo,
    String mensaje,
    java.util.Map<String, String> detalles
) {

    public static RespuestaError de(String codigo, String mensaje) {
        return new RespuestaError(codigo, mensaje, java.util.Map.of());
    }
}