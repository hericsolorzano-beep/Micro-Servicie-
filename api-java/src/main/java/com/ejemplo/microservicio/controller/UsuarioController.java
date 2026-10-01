package com.ejemplo.microservicio.controller;

import com.ejemplo.microservicio.domain.Usuario;
import com.ejemplo.microservicio.dto.RespuestaFraude;
import com.ejemplo.microservicio.dto.RespuestaTransaccion;
import com.ejemplo.microservicio.dto.RespuestaUsuario;
import com.ejemplo.microservicio.dto.SolicitudTransaccion;
import com.ejemplo.microservicio.exception.AccesoDenegadoException;
import com.ejemplo.microservicio.exception.ServicioIANoDisponibleException;
import com.ejemplo.microservicio.exception.UsuarioNoEncontradoException;
import com.ejemplo.microservicio.security.ComprobadorDePropiedad;
import com.ejemplo.microservicio.service.TransaccionService;
import com.ejemplo.microservicio.service.UsuarioService;
import jakarta.validation.Valid;
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
    private final ComprobadorDePropiedad propiedad;

    public UsuarioController(UsuarioService usuarios,
                             TransaccionService transacciones,
                             ComprobadorDePropiedad propiedad) {
        this.usuarios = usuarios;
        this.transacciones = transacciones;
        this.propiedad = propiedad;
    }

    @GetMapping("/usuarios/{id}")
    public ResponseEntity<RespuestaUsuario> perfil(@PathVariable Long id) {

        // Se comprueba la propiedad ANTES de tocar la base de datos: si el
        // token no es de este usuario, la respuesta es 404 sin revelar si
        // el usuario existe.
        propiedad.exigir(id, usuarios);

        Usuario usuario = usuarios.buscarPorId(id);
        if (usuario == null) {
            throw new UsuarioNoEncontradoException(id);
        }
        return ResponseEntity.ok(RespuestaUsuario.desde(usuario));
    }

    /**
     * Analiza una transaccion y la guarda.
     */
    @PostMapping("/usuarios/{id}/transacciones")
    public ResponseEntity<RespuestaFraude> analizar(
            @PathVariable Long id,
            @Valid @RequestBody SolicitudTransaccion solicitud)
            throws ServicioIANoDisponibleException {

        // Propiedad primero: un token ajeno no debe poder gastar una
        // inferencia, ni aunque el usuario exista.
        propiedad.exigir(id, usuarios);

        // Luego existencia: si no existe, gastaria una inferencia y un
        // hueco en el modelo de riesgo para nada.
        if (usuarios.buscarPorId(id) == null) {
            throw new UsuarioNoEncontradoException(id);
        }

        RespuestaFraude respuesta = transacciones.analizarYGuardar(id, solicitud);
        return ResponseEntity.ok(respuesta);
    }

    /**
     * Historial del usuario, paginado.
     *
     * El limite NO es opcional: sin el, "dame todo" carga la tabla
     * entera en memoria. El maximo de 100 impide que un solo cliente
     * pida la tabla entera.
     *
     * Las transacciones de un usuario solo se ven en las rutas que
     * llevan SU id (/usuarios/{id}/transacciones). No hay endpoint
     * global, y esa es la decision de seguridad que mas importa aqui:
     * sin un parametro de propietario, cualquier cliente autenticado
     * podria listar la transaccion de cualquiera.
     */
    @GetMapping("/usuarios/{id}/transacciones")
    public ResponseEntity<List<RespuestaTransaccion>> historial(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int pagina,
            @RequestParam(defaultValue = "20") int tamano,
            @RequestParam(defaultValue = "false") boolean soloFraude) {

        // 404 y no 403: un 403 confirmaria que el usuario existe, y
        // permitiria enumerarlos probando identificadores. propiedad.exigir
        // ya lanza AccesoDenegadoException.comoSiNoExistiera() cuando el
        // token es de otro usuario.
        propiedad.exigir(id, usuarios);

        if (usuarios.buscarPorId(id) == null) {
            throw AccesoDenegadoException.comoSiNoExistiera();
        }

        int tamanoSeguro = Math.min(Math.max(tamano, 1), 100);
        var page = PageRequest.of(Math.max(pagina, 0), tamanoSeguro);

        List<RespuestaTransaccion> resultado =
                transacciones.historial(id, soloFraude, page).stream()
                        .map(RespuestaTransaccion::desde)
                        .toList();

        return ResponseEntity.ok(resultado);
    }
}