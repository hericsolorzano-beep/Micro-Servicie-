package com.ejemplo.microservicio.controller;

import com.ejemplo.microservicio.dto.RespuestaFraude;
import com.ejemplo.microservicio.dto.RespuestaSentimiento;
import com.ejemplo.microservicio.dto.SolicitudTexto;
import com.ejemplo.microservicio.dto.SolicitudTransaccion;
import com.ejemplo.microservicio.exception.ServicioIANoDisponibleException;
import com.ejemplo.microservicio.service.AnalisisService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints publicos.
 *
 * El controller es delgado a proposito: traduce HTTP a Java y viceversa,
 * nada mas. Si aparece una regla de negocio aqui, es una señal de que la
 * logica debe bajar al service.
 */
@RestController
@RequestMapping("/api")
public class AnalisisController {

    private final AnalisisService analisisService;

    public AnalisisController(AnalisisService analisisService) {
        this.analisisService = analisisService;
    }

    /**
     * Analiza una transaccion bancaria.
     *
     * @Valid activa la cascada de anotaciones del record. Sin el, las
     * restricciones se declaran pero nunca se comprueban: el controlador
     * recibe null y revienta mas abajo, con un 500 en lugar de un 400.
     */
    @PostMapping("/transacciones")
    public ResponseEntity<RespuestaFraude> analizar(
            @Valid @RequestBody SolicitudTransaccion solicitud)
            throws ServicioIANoDisponibleException {

        RespuestaFraude respuesta = analisisService.analizarTransaccion(solicitud);
        return ResponseEntity.ok(respuesta);
    }

    @PostMapping("/texto")
    public ResponseEntity<RespuestaSentimiento> analizarTexto(
            @Valid @RequestBody SolicitudTexto solicitud)
            throws ServicioIANoDisponibleException {

        return ResponseEntity.ok(analisisService.analizarSentimiento(solicitud));
    }
}