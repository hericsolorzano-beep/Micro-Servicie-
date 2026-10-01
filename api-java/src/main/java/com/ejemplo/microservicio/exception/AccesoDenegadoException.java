package com.ejemplo.microservicio.exception;

/**
 * El usuario existe pero no se le permite tocar ese recurso.
 *
 * Se separa de UsuarioNoEncontradoException (404) a proposito, aunque las
 * dos signifiquen "no puedes ver esto":
 *
 * - 403 dice "existe pero no es tuyo": informa de la existencia del dato.
 * - 404 no revela ni si existe: es la practica habitual para recursos
 *   privados, porque el 403 permite enumerar usuarios validos probando
 *   identificadores.
 *
 * Por defecto se usa 404 en las lecturas de transacciones. El 403 queda
 * disponible para cuando el dato este realmente accesible y solo haya
 * una restriccion de operacion.
 */
public class AccesoDenegadoException extends RuntimeException {

    public AccesoDenegadoException(String mensaje) {
        super(mensaje);
    }

    /** 404: no se confirma la existencia del recurso. */
    public static AccesoDenegadoException comoSiNoExistiera() {
        return new AccesoDenegadoException("El recurso solicitado no existe");
    }
}