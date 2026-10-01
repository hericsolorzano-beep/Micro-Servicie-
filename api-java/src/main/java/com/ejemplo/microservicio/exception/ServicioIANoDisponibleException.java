package com.ejemplo.microservicio.exception;

/**
 * Falla al comunicarse con el servicio de IA.
 *
 * Eschecked (extends Exception) a proposito: obliga a cada llamada a
 * decidir explicitamente que hacer cuando la inferencia no esta
 * disponible. Un RuntimeException se propaga sinavisar y termina en un
 * 500 opaco en el log.
 */
public class ServicioIANoDisponibleException extends Exception {

    public ServicioIANoDisponibleException(String mensaje) {
        super(mensaje);
    }

    public ServicioIANoDisponibleException(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }
}