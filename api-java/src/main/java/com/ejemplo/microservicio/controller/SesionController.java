package com.ejemplo.microservicio.controller;

import com.ejemplo.microservicio.dto.SolicitudSesion;
import com.ejemplo.microservicio.dto.SolicitudRegistro;
import com.ejemplo.microservicio.dto.RespuestaToken;
import com.ejemplo.microservicio.dto.RespuestaUsuario;
import com.ejemplo.microservicio.exception.RespuestaError;
import com.ejemplo.microservicio.security.EmisorDeToken;
import com.ejemplo.microservicio.service.UsuarioService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Alta de usuarios y apertura de sesion.
 *
 * Es la unica parte de la API sin token: es la puerta de entrada. Todo lo
 * demas lo exige.
 */
@RestController
@RequestMapping(SesionController.RUTA_BASE)
public class SesionController {

    public static final String RUTA_BASE = "/api/v1/sesiones";

    /** Ruta de login. Publica por definicion: es la puerta de entrada. */
    public static final String RUTA_LOGIN = RUTA_BASE + "/login";

    private static final Logger log = LoggerFactory.getLogger(SesionController.class);

    private final UsuarioService usuarios;
    private final EmisorDeToken emisor;

    public SesionController(UsuarioService usuarios, EmisorDeToken emisor) {
        this.usuarios = usuarios;
        this.emisor = emisor;
    }

    /**
     * Alta de usuario. Publica: todavia no hay token.
     *
     * En un sistema real este endpoint se limitaria (por ejemplo, solo
     * desde una IP de confianza, o con un codigo de invitacion), porque
     * permitir el autoservicio abierto es una via de registro masivo.
     */
    @PostMapping("/usuarios")
    public ResponseEntity<RespuestaUsuario> registrar(
            @Valid @RequestBody SolicitudRegistro solicitud) {

        var creado = usuarios.crear(
                solicitud.email(), solicitud.nombre(), solicitud.contrasena());

        return ResponseEntity.status(201).body(RespuestaUsuario.desde(creado));
    }

    /**
     * Login: email y contrasena a cambio de un token.
     *
     * El mensaje de error es el MISMO para "usuario no existe" y para
     * "contrasena incorrecta". Distinguirlos permitiria enumerar quais
     * emails estan registrados probando senales en la respuesta y en el
     * tiempo de respuesta.
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(
            @Valid @RequestBody SolicitudSesion solicitud) {

        if (!usuarios.credencialesValidas(solicitud.email(), solicitud.contrasena())) {
            log.warn("Intento de login fallido para {}", solicitud.email());
            // Mismo cuerpo para "no existe" y para "contrasena erronea".
            // Distinguirlos permitiria enumerar que emails estan
            // registrados, y ademas por el tiempo de respuesta.
            return ResponseEntity.status(401).body(
                    RespuestaError.de("CREDENCIALES_INVALIDAS",
                            "Credenciales invalidas"));
        }

        var usuario = usuarios.buscarPorEmail(solicitud.email());
        String token = emisor.emitir(usuario.getId(), usuario.getEmail());

        log.info("Sesion iniciada para usuario {}", usuario.getId());

        return ResponseEntity.ok(new RespuestaToken(
                token, "Bearer", usuario.getId()));
    }
}