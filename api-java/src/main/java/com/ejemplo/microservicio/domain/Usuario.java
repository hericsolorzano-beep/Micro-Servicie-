package com.ejemplo.microservicio.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Usuario del sistema.
 *
 * Usuario es palabra reservada en el dialecto de PostgreSQL, asi que la
 * tabla se llama "usuario" (singular) y la entidad se llama Usuario. El
 * nombre de la CLASE puede ser el que quiera; el problema seria el de la
 * tabla.
 *
 * No hay setters: la entidad se construye con un constructor y sus campos
 * son final. Eso hace que un objeto no pueda cambiar a mitad de camino
 * mientras Hibernate lo tiene en la sesion, que es una fuente clasica de
 * exotico en entidades JPA.
 */
@Entity
@Table(name = "usuario")
public class Usuario {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 255)
    private String email;

    @Column(nullable = false, length = 100)
    private String nombre;

    /**
     * Hash BCrypt de la contrasena. Nunca la contrasena en claro.
     *
     * BCrypt lleva el salt dentro del propio hash, asi que dos usuarios con
     * la misma contrasena producen cadenas distintas. Eso es lo que impide
     * un ataque de tabla arcoiris.
     */
    @Column(name = "hash_contrasena", nullable = false, length = 100)
    private String hashContrasena;

    @Column(nullable = false)
    private boolean activo = true;

    @Column(name = "creado_en", nullable = false)
    private Instant creadoEn = Instant.now();

    /** Lo usa JPA. protected, no public. */
    protected Usuario() {
    }

    public Usuario(String email, String nombre, String hashContrasena) {
        this.email = email;
        this.nombre = nombre;
        this.hashContrasena = hashContrasena;
        this.activo = true;
        this.creadoEn = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getNombre() {
        return nombre;
    }

    public String getHashContrasena() {
        return hashContrasena;
    }

    public boolean isActivo() {
        return activo;
    }

    public void desactivar() {
        this.activo = false;
    }

    public Instant getCreadoEn() {
        return creadoEn;
    }
}