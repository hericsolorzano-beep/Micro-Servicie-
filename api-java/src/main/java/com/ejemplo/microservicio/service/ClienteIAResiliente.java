package com.ejemplo.microservicio.service;

import com.ejemplo.microservicio.client.ClienteIAService;
import com.ejemplo.microservicio.dto.PeticionFraudePython;
import com.ejemplo.microservicio.dto.PeticionTextoPython;
import com.ejemplo.microservicio.dto.RespuestaFraudePython;
import com.ejemplo.microservicio.dto.RespuestaSentimientoPython;
import com.ejemplo.microservicio.exception.ServicioIANoDisponibleException;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Calls to the AI service wrapped in resilience patterns.
 *
 * WHY a separate class instead of annotations on AnalisisService:
 * Resilience4j works through Spring proxies. If the annotated method is
 * called from within the same class, the proxy is bypassed and the
 * protection silently does nothing. The code LOOKS protected and is not.
 * Keeping the annotated methods here, called only from AnalisisService,
 * removes that trap entirely.
 *
 * The order of the annotations is not cosmetic. The circuit breaker must
 * wrap the retry, so that one failed request counts as one failure and not
 * three. That ordering is what circuitBreakerAspectOrder controls; set in
 * application.yml.
 *
 * Each pattern solves a different failure:
 *   - Retry:     transient failures, Python restarting for example
 *   - Breaker:   sustained failure, avoids hammering a service that is down
 *   - Bulkhead:  load shedding, keeps one slow dependency from consuming
 *                every thread in the pool
 *
 * The bulkhead is in SEMAPHORE mode, not THREADPOOL. THREADPOOL only works
 * with async calls (CompletableFuture); against a synchronous call it
 * throws IllegalStateException("ThreadPool bulkhead is only applicable for
 * completable futures") at runtime. The semaphore is the right choice here
 * and works on the thread that was already in use.
 */
@Service
public class ClienteIAResiliente {

    private static final Logger log = LoggerFactory.getLogger(ClienteIAResiliente.class);

    private final ClienteIAService clienteIA;

    public ClienteIAResiliente(ClienteIAService clienteIA) {
        this.clienteIA = clienteIA;
    }

    @CircuitBreaker(name = "servicioIA")
    @Retry(name = "servicioIA")
    @Bulkhead(name = "servicioIA", type = Bulkhead.Type.SEMAPHORE)
    public RespuestaFraudePython predecirFraude(PeticionFraudePython peticion)
            throws ServicioIANoDisponibleException {

        log.debug("Llamando a /predict/fraude");
        return clienteIA.predecirFraude(peticion);
    }

    @CircuitBreaker(name = "servicioIA")
    @Retry(name = "servicioIA")
    @Bulkhead(name = "servicioIA", type = Bulkhead.Type.SEMAPHORE)
    public RespuestaSentimientoPython predecirSentimiento(PeticionTextoPython peticion)
            throws ServicioIANoDisponibleException {

        log.debug("Llamando a /predict/sentimiento");
        return clienteIA.predecirSentimiento(peticion);
    }
}