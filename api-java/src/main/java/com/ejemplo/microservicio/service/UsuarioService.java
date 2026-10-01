package com.ejemplo.microservicio.service;

import com.ejemplo.microservicio.domain.Usuario;
import com.ejemplo.microservicio.repository.UsuarioRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Alta y consulta de usuarios.
 *
 * PasswordEncoder (BCrypt) se usa SIEMPRE. Guardar la contrasena en claro
 * es el error mas grave posible en este dominio: una filtracion de la base
 * de datos entrega las contrasenas de todos los usuarios, y como la gente
 * reutiliza contrasenas, tambien las de otros servicios.
 */
@Service
public class UsuarioService {

    private final UsuarioRepository repositorio;
    private final PasswordEncoder encoder;

    public UsuarioService(UsuarioRepository repositorio, PasswordEncoder encoder) {
        this.repositorio = repositorio;
        this.encoder = encoder;
    }

    /**
     * Da de alta un usuario.
     *
     * La contrasena se hashea ANTES de tocar la entidad, nunca despues:
     * si la entidad guardara un instante la contrasena en claro y la
     * peticion fallara a mitad (email duplicado, por ejemplo), podria
     * quedar persistida en claro.
     */
    @Transactional
    public Usuario crear(String email, String nombre, String contrasena) {
        if (repositorio.existsByEmailIgnoreCase(email)) {
            throw new IllegalArgumentException("Ya existe un usuario con ese email");
        }
        String hash = encoder.encode(contrasena);
        return repositorio.save(new Usuario(email, nombre, hash));
    }

    @Transactional(readOnly = true)
    public Usuario buscarPorEmail(String email) {
        return repositorio.findByEmailIgnoreCase(email).orElse(null);
    }

    @Transactional(readOnly = true)
    public Usuario buscarPorId(Long id) {
        return repositorio.findById(id).orElse(null);
    }

    /**
     * Comprueba las credenciales.
     *
     * Devuelve boolean, no el usuario: asi esta funcion no puede usarse
     * por accidente como "quien es este usuario" y filtrar informacion de
     * cuentas ajenas.
     */
    @Transactional(readOnly = true)
    public boolean credencialesValidas(String email, String contrasena) {
        return repositorio.findByEmailIgnoreCase(email)
                .filter(Usuario::isActivo)
                .map(u -> encoder.matches(contrasena, u.getHashContrasena()))
                .orElse(false);
    }
}