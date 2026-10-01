package com.ejemplo.microservicio.service;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Reporta en /actuator/health la salud de la IA y si la firma de tokens
 * esta bien configurada.
 *
 * El segundo dato parece raro y es el mas importante. La seguridad ya no
 * se puede desactivar, pero el secreto con el que se firman los tokens si
 * se puede olvidar, y el olvido no produce ningun fallo visible: la API
 * responde 200 a todo el mundo y lo unico que ocurre es que cada
 * reinicio invalida las sesiones abiertas.
 *
 * Publicar el estado convierte ese olvido en algo visible en un health
 * check, que es donde se mira de verdad. El aviso se deja como UP y no
 * como DOWN porque el servicio funciona: mentir en el estado de salud
 * hace que quien lo vigile deje de mirar el indicador.
 */
@Component
public class SeguridadYResilienciaIndicator implements HealthIndicator {

    private final com.ejemplo.microservicio.config.EstadoClaveJwt clave;
    private final CircuitBreakerRegistry registro;

    public SeguridadYResilienciaIndicator(
            com.ejemplo.microservicio.config.EstadoClaveJwt clave,
            CircuitBreakerRegistry registro) {
        this.clave = clave;
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

        builder.withDetail("autenticacion", "JWT");
        builder.withDetail("clave_jwt_definida", clave.definida());

        if (!clave.definida()) {
            builder.withDetail("aviso",
                    "JWT_SECRET no esta definido: se firma con una clave "
                    + "generada en cada arranque, asi que las sesiones "
                    + "abiertas mueren en cada reinicio. Define JWT_SECRET "
                    + "antes de exponer el servicio.");
        }

        if (breaker.getState() == CircuitBreaker.State.OPEN) {
            builder.withDetail("llamadas_rechazadas_por_circuito",
                    breaker.getMetrics().getNumberOfFailedCalls());
        }

        return builder.build();
    }
}
