package com.ejemplo.microservicio.exception;

/**
 * El usuario indicado no existe.
 *
 * Excepcion de dominio, no de infraestructura. El service la lanza y el
 * ManejadorErrores la traduce a 404; el repositorio no sabe nada de HTTP.
 */
public class UsuarioNoEncontradoException extends RuntimeException {

    private final Long id;

    public UsuarioNoEncontradoException(Long id) {
        super("No existe el usuario con id " + id);
        this.id = id;
    }

    public Long getId() {
        return id;
    }
}