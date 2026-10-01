package com.ejemplo.microservicio.service;

import com.ejemplo.microservicio.domain.Transaccion;
import com.ejemplo.microservicio.dto.PeticionFraudePython;
import com.ejemplo.microservicio.dto.RespuestaFraude;
import com.ejemplo.microservicio.dto.RespuestaFraudePython;
import com.ejemplo.microservicio.dto.SolicitudTransaccion;
import com.ejemplo.microservicio.exception.UsuarioNoEncontradoException;
import com.ejemplo.microservicio.exception.ServicioIANoDisponibleException;
import com.ejemplo.microservicio.repository.TransaccionRepository;
import com.ejemplo.microservicio.domain.Usuario;
import com.ejemplo.microservicio.repository.UsuarioRepository;
import com.ejemplo.microservicio.repository.ComponenteRepository;
import com.ejemplo.microservicio.domain.Componente;
import java.math.BigDecimal;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Analiza transacciones y las persiste.
 *
 * THE IMPORTANT DECISION in this service is WHAT GETS SAVED WHEN THE AI
 * FAILS. The response to the client is unchanged (503), but the
 * transaction is saved anyway, with errorAnalisis set and no verdict.
 *
 * If it were not saved, the record of everything attempted while the AI
 * was down would be lost: exactly the operations that need the most
 * review later. And if it were saved without telling apart "evaluated and
 * clean" from "never evaluated", a fraud would look approved.
 *
 * That is why this method is NOT transactional: if it were, the exception
 * from the AI would roll back the write along with everything else.
 */
@Service
public class TransaccionService {

    private static final Logger log = LoggerFactory.getLogger(TransaccionService.class);

    /** Nº de componentes PCA que espera el modelo. */
    static final int NUM_COMPONENTES = 28;

    private final ClienteIAResiliente clienteIA;
    private final TransaccionRepository repositorio;
    private final UsuarioRepository usuarios;
    private final ComponenteRepository componentes;

    public TransaccionService(ClienteIAResiliente clienteIA,
                              TransaccionRepository repositorio,
                              UsuarioRepository usuarios,
                              ComponenteRepository componentes) {
        this.clienteIA = clienteIA;
        this.repositorio = repositorio;
        this.usuarios = usuarios;
        this.componentes = componentes;
    }

    /**
     * Analiza y guarda. El analisis va FUERA de la transaccion (por eso el
     * servicio no lleva @Transactional); la escritura, DENTRO.
     *
     * @throws ServicioIANoDisponibleException si la IA no responde. La
     *         transaccion queda guardada igualmente.
     * @throws UsuarioNoEncontradoException si el usuario no existe
     */
    public RespuestaFraude analizarYGuardar(Long usuarioId,
                                            SolicitudTransaccion solicitud)
            throws ServicioIANoDisponibleException {

        Usuario usuario = usuarios.findById(usuarioId)
                .orElseThrow(() -> new UsuarioNoEncontradoException(usuarioId));

        long inicio = System.nanoTime();

        Transaccion transaccion = new Transaccion(
                usuario,
                BigDecimal.valueOf(solicitud.monto()),
                solicitud.hora(),
                solicitud.pais(),
                BigDecimal.valueOf(solicitud.distanciaKm()),
                solicitud.componentes().size());

        try {
            PeticionFraudePython peticion = new PeticionFraudePython(
                    solicitud.componentes(),
                    solicitud.monto(),
                    solicitud.hora(),
                    solicitud.pais(),
                    solicitud.distanciaKm());

            RespuestaFraudePython respuesta = clienteIA.predecirFraude(peticion);
            String accion = decidirAccion(respuesta);

            // El tiempo lo medimos aqui, no lo pedimos a Python: el
            // dato que importa es el tiempo de pared que ve el cliente,
            // red incluida.
            long ms = (System.nanoTime() - inicio) / 1_000_000;

            transaccion.registrarVeredicto(
                    respuesta.esFraude(),
                    BigDecimal.valueOf(respuesta.probabilidad()),
                    respuesta.nivelRiesgo(),
                    respuesta.modelo(),
                    accion,
                    ms,
                    respuesta.umbral());

            guardar(transaccion, solicitud.componentes());

            log.info("Transaccion pais={} -> {} accion={} (guardada)",
                    solicitud.pais(), respuesta.esFraude(), accion);

            return RespuestaFraude.de(respuesta, accion, ms);

        } catch (ServicioIANoDisponibleException e) {
            // Saved WITHOUT a verdict, and the 503 is propagated. Both
            // are needed: the client knows there is no verdict, and the
            // auditor knows the operation existed.
            transaccion.registrarErrorAnalisis(e.getMessage());
            guardar(transaccion, solicitud.componentes());

            log.warn("Transaccion registrada sin analizar: {}", e.getMessage());

            throw e;
        }
    }

    /**
     * Write in its own transaction.
     *
     * REQUIRES_NEW forces a new one: if it were called inside another
     * transaction that later fails, that rollback would take this write
     * down with it, which is exactly what must be avoided here.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Transaccion guardar(Transaccion transaccion, List<Double> componentesPca) {
        Transaccion guardada = repositorio.save(transaccion);
        Componente.guardarTodas(componentes, guardada, componentesPca);
        return guardada;
    }

    /**
     * Historial del usuario.
     *
     * El mapeo a DTO ocurre DENTRO de la transaccion a proposito: la
     * relacion con Usuario es LAZY, asi que fuera de ella cualquier acceso
     * lanzaria LazyInitializationException.
     */
    @Transactional(readOnly = true)
    public List<Transaccion> historial(Long usuarioId, boolean soloFraude,
                                       Pageable pageable) {
        return repositorio.buscar(usuarioId, soloFraude, pageable);
    }

    @Transactional(readOnly = true)
    public long totalTransacciones(Long usuarioId) {
        return repositorio.countByUsuarioId(usuarioId);
    }

    @Transactional(readOnly = true)
    public long totalFraudes(Long usuarioId) {
        return repositorio.countByUsuarioIdAndEsFraudeTrue(usuarioId);
    }

    /**
     * Same rules that decide an action, now applied to persisted data.
     *
     * The default case escalates rather than approves: an unknown level
     * from the model must reach a human, never be waved through.
     */
    private String decidirAccion(RespuestaFraudePython respuesta) {
        if (!respuesta.esFraude()) {
            return "APROBADA";
        }
        return switch (respuesta.nivelRiesgo()) {
            case "critico" -> "BLOQUEADA";
            case "alto" -> "REQUIERE_REVISION";
            case "medio" -> "MONITORIZAR";
            default -> "REQUIERE_REVISION";
        };
    }
}