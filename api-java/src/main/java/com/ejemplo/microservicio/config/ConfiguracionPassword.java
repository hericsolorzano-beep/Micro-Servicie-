package com.ejemplo.microservicio.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Codec de contrasenas.
 *
 * Se declara como bean para que el algoritmo se cambie en un unico sitio.
 * Si cada clase creara su propio BCryptPasswordEncoder, migrar a Argon2
 * mas adelante seria buscar y reemplazar.
 */
@Configuration
public class ConfiguracionPassword {

    /**
     * BCrypt con factor de coste 10 (~100ms por hash en hardware actual).
     *
     * El factor es el parametro clave: cada incremento duplica el coste.
     * Se sube solo, nunca se baja, porque subirlo no invalida los hashes
     * antiguos (cada hash guarda su propio factor) y evita la Migracion.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }
}