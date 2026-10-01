package com.ejemplo.microservicio.controller;

import com.ejemplo.microservicio.domain.Usuario;
import com.ejemplo.microservicio.dto.RespuestaTransaccion;
import com.ejemplo.microservicio.dto.RespuestaUsuario;
import com.ejemplo.microservicio.dto.SolicitudRegistro;
import com.ejemplo.microservicio.dto.SolicitudTransaccion;
import com.ejemplo.microservicio.exception.ServicioIANoDisponibleException;
import com.ejemplo.microservicio.service.TransaccionService;
import com.ejemplo.microservicio.service.UsuarioNoEncontradoException;
import com.ejemplo.microservicio.service.UsuarioService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints con persistencia.
 *
 * Devuelve 201 con Location en el alta, que es lo que dice el protocolo
 * HTTP: el recurso se creo y esta aqui.
 */
@RestController
@RequestMapping("/api/v1")
public class UsuarioController {

    private final UsuarioService usuarios;
    private final TransaccionService transacciones;

    public UsuarioController(UsuarioService usuarios,
                             TransaccionService transacciones) {
        this.usuarios = usuarios;
        this.transacciones = transacciones;
    }

    /**
     * Alta de usuario. 201 + cabecera Location.
     */
    @PostMapping("/usuarios")
    public ResponseEntity<RespuestaUsuario> registrar(
            @Valid @RequestBody SolicitudRegistro solicitud) {

        Usuario creado = usuarios.crear(
                solicitud.email(), solicitud.nombre(), solicitud.contrasena());

        return ResponseEntity
                .created(URI.create("/api/v1/usuarios/" + creado.getId()))
                .body(RespuestaUsuario.desde(creado));
    }

    /**
     * Perfil del usuario.
     *
     * Devuelve 404 si no existe, no null con 200: un JSON null obliga al
     * cliente a adivinar si fallo o si no hay contenido.
     */
    @GetMapping("/usuarios/{id}")
    public ResponseEntity<RespuestaUsuario> perfil(@PathVariable Long id) {
        Usuario usuario = usuarios.buscarPorId(id);
        if (usuario == null) {
            throw new UsuarioNoEncontradoException(id);
        }
        return ResponseEntity.ok(RespuestaUsuario.desde(usuario));
    }

    /**
     * Analiza una transaccion y la guarda.
     *
     * Mismo comportamiento que /api/transacciones mas la persistencia.
     * Se mantiene el endpoint antiguo sin usuario para no romper el
     * contrato existente.
     */
    @PostMapping("/usuarios/{id}/transacciones")
    public ResponseEntity<?> analizar(
            @PathVariable Long id,
            @Valid @RequestBody SolicitudTransaccion solicitud)
            throws ServicioIANoDisponibleException {

        var respuesta = transacciones.analizarYGuardar(id, solicitud);
        return ResponseEntity.ok(respuesta);
    }

    /**
     * Historial del usuario.
     *
     * El limite de pagina NO es opcional: sin el, "dame todo" carga toda
     * la tabla en memoria. El maximo de 100 impide que un solo cliente
     * pida la tabla entera.
     */
    @GetMapping("/usuarios/{id}/transacciones")
    public ResponseEntity<List<RespuestaTransaccion>> historial(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int pagina,
            @RequestParam(defaultValue = "20") int tamano,
            @RequestParam(defaultValue = "false") boolean soloFraude) {

        int tamanoSeguro = Math.min(Math.max(tamano, 1), 100);
        var page = PageRequest.of(Math.max(pagina, 0), tamanoSeguro);

        List<RespuestaTransaccion> resultado =
                transacciones.historial(id, soloFraude, page).stream()
                        .map(RespuestaTransaccion::desde)
                        .toList();

        return ResponseEntity.ok(resultado);
    }
}