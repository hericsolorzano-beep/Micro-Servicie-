package com.ejemplo.microservicio.service;

import com.ejemplo.microservicio.security.PropiedadesSeguridad;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Reporta en /actuator/health tanto la salud de la IA como si la API
 * tiene la seguridad activada.
 *
 * El segundo dato parece raro y es el mas importante. La seguridad viene
 * desactivada por defecto para que el desarrollo local sea comodo, y esa
 * comodidad tiene un precio peligroso: si un despliegue se olvida de
 * definir SEGURIDAD_ACTIVADA, la API queda abierta y nadie se entera
 * hasta que alguien la encuentra.
 *
 * Publicar el estado convierte un olvido en algo visible en un health
 * check, que es donde se mira de verdad.
 */
@Component
public class SeguridadYResilienciaIndicator implements HealthIndicator {

    private final PropiedadesSeguridad seguridad;
    private final CircuitBreakerRegistry registro;

    public SeguridadYResilienciaIndicator(
            PropiedadesSeguridad seguridad,
            CircuitBreakerRegistry registro) {
        this.seguridad = seguridad;
        this.registro = registro;
    }

    @Override
    public Health health() {
        CircuitBreaker breaker = registro.circuitBreaker("servicioIA");

        Health.Builder builder = breaker.getState() == CircuitBreaker.State.OPEN
                // DOWN cuando el circuito esta abierto: el servicio de IA
                // esta fallando de forma sostenida y no tiene sentido
                // seguir gastando llamadas en el. Que figure en el health
                // hace visible un problema que si no solo se veria en el
                // log.
                ? Health.down().withDetail("circuito", "ABIERTO")
                : Health.up().withDetail("circuito", breaker.getState().name());

        builder.withDetail("autenticacion_activada", seguridad.activada());

        if (!seguridad.activada()) {
            // No es un fallo: la API responde, y en local es lo que se
            // quiere. Se deja como UP con el detalle a la vista, para que
            // quien mire el health vea que no hay autenticacion.
            builder.withDetail("aviso",
                    "API sin autenticacion. Define SEGURIDAD_ACTIVADA=true "
                    + "antes de exponerla fuera de la red local.");
        }

        if (breaker.getState() == CircuitBreaker.State.OPEN) {
            builder.withDetail("llamadas_rechazadas_por_circuito",
                    breaker.getMetrics().getNumberOfFailedCalls());
        }

        return builder.build();
    }
}