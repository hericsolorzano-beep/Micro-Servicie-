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

    /**
     * Hash de relleno, para igualar el tiempo cuando el usuario no existe.
     *
     * Ver credencialesValidas(): sin esto, un login contra un email
     * inexistente tardaba 6,8 veces menos que contra uno existente, y la
     * diferencia se mide por red sin dificultad. El codigo devolvido es el
     * mismo, pero el tiempo no: ese es el oraculo.
     *
     * El valor es el hash de una contrasena cualquiera, con el mismo
     * factor de coste que usa el encoder real. Tiene que ser un hash
     * VALIDO de BCrypt, porque matches() solo acepta uno.
     */
    private final String hashDeRelleno;

    public UsuarioService(UsuarioRepository repositorio, PasswordEncoder encoder) {
        this.repositorio = repositorio;
        this.encoder = encoder;
        this.hashDeRelleno = encoder.encode("relleno-para-igualar-tiempos");
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
        Usuario usuario = repositorio.findByEmailIgnoreCase(email)
                .filter(Usuario::isActivo)
                .orElse(null);

        if (usuario == null) {
            // Se verifica CONTRA UN HASH DE RELLENO y se descarta el
            // resultado. Es trabajo inútil a proposito: sin el, este camino
            // devuelve en milisegundos y el camino con usuario real tarda
            // lo que tarda BCrypt (~100 ms).
            //
            // Medido contra el stack en ejecucion antes del arreglo:
            //   usuario existente:   108,4 ms
            //   usuario inexistente:   16,0 ms   (6,8x mas rapido)
            //
            // Con el mismo cuerpo de respuesta y un tiempo tan distinto, se
            // enumeran cuentas registradas probando emails. No hace falta
            // adivinar contrasenas ni victim's sesion: basta con medir.
            encoder.matches(contrasena, hashDeRelleno);
            return false;
        }

        return encoder.matches(contrasena, usuario.getHashContrasena());
    }
}