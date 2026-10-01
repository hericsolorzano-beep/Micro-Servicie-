package com.ejemplo.microservicio.service;

import com.ejemplo.microservicio.dto.PeticionFraudePython;
import com.ejemplo.microservicio.dto.PeticionTextoPython;
import com.ejemplo.microservicio.dto.RespuestaFraude;
import com.ejemplo.microservicio.dto.RespuestaFraudePython;
import com.ejemplo.microservicio.dto.RespuestaSentimiento;
import com.ejemplo.microservicio.dto.RespuestaSentimientoPython;
import com.ejemplo.microservicio.dto.SolicitudTexto;
import com.ejemplo.microservicio.dto.SolicitudTransaccion;
import com.ejemplo.microservicio.exception.ServicioIANoDisponibleException;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Logica de negocio que combina datos del cliente con la inferencia.
 *
 * Aqui ocurre lo que Python no puede (ni debe) saber: las reglas de
 * negocio. El modelo dice "esto parece fraude"; aqui se decide si eso
 * bloquea una cuenta, genera una alerta o solo se registra.
 *
 * Esa separacion es lo que hace que el modelo sea reemplazable: cambiar
 * el dataset o el algoritmo no altera ni una linea de este archivo.
 */
@Service
public class AnalisisService {

    private static final Logger log = LoggerFactory.getLogger(AnalisisService.class);

    /**
     * El cliente con resiliencia, no el cliente HTTP directo. La
     * indireccion existe para que el circuit breaker, el retry y el
     * bulkhead se apliquen SIEMPRE en esta frontera, sin que ningun
     * llamante tenga que acordarse de invocarlos.
     */
    private final ClienteIAResiliente clienteIA;

    public AnalisisService(ClienteIAResiliente clienteIA) {
        this.clienteIA = clienteIA;
    }

    /**
     * Analiza una transaccion y devuelve el veredicto mas la accion.
     */
    public RespuestaFraude analizarTransaccion(SolicitudTransaccion solicitud)
            throws ServicioIANoDisponibleException {

        long inicio = System.nanoTime();

        // Conversion publica -> privada. El unico punto donde ocurre.
        PeticionFraudePython peticion = new PeticionFraudePython(
                solicitud.componentes(),
                solicitud.monto(),
                solicitud.hora(),
                solicitud.pais(),
                solicitud.distanciaKm());

        RespuestaFraudePython respuesta = clienteIA.predecirFraude(peticion);
        long ms = (System.nanoTime() - inicio) / 1_000_000;

        String accion = decidirAccion(respuesta);

        // Log sin el monto: en un entorno real el importe de una
        // transaccion es dato financiero y no debe quedar en el log.
        //
        // El formato se construye aqui y se pasa ya formateado como un
        // unico argumento. SLF4J solo reconoce "{}" como marcador:
        // escribir "{:.1%}" ahi lo imprime literalmente y desplaza los
        // argumentos, de modo que "accion=" acaba mostrando la
        // probabilidad y la latencia se pierde.
        //
        // Locale.ROOT evita que un servidor en espanol escriba "91,2%".
        log.info("Transaccion pais={} -> {} prob={} accion={} en {}ms",
                solicitud.pais(),
                respuesta.esFraude(),
                String.format(Locale.ROOT, "%.1f%%",
                        respuesta.probabilidad() * 100),
                accion,
                ms);

        return RespuestaFraude.de(respuesta, accion, ms);
    }

    /**
     * Reglas de negocio que consumen la salida del modelo.
     *
     * El modelo da la probabilidad y una etiqueta; la severidad operativa
     * la decide negocio, no el clasificador. Y el nivel desconocido escala
     * en vez de aprobar: aprobar es el error caro y no se deshace.
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

    public RespuestaSentimiento analizarSentimiento(SolicitudTexto solicitud)
            throws ServicioIANoDisponibleException {

        long inicio = System.nanoTime();

        RespuestaSentimientoPython respuesta =
                clienteIA.predecirSentimiento(new PeticionTextoPython(solicitud.texto()));

        long ms = (System.nanoTime() - inicio) / 1_000_000;

        // Tampoco se registra el texto analizado: puede contener datos
        // personales del usuario. Mismo criterio de formato y locale.
        log.info("Sentimiento -> {} prob={} en {}ms",
                respuesta.sentimiento(),
                String.format(Locale.ROOT, "%.1f%%",
                        respuesta.confianza() * 100),
                ms);

        return new RespuestaSentimiento(
                respuesta.sentimiento(),
                respuesta.confianza(),
                respuesta.probabilidades(),
                respuesta.modelo());
    }
}