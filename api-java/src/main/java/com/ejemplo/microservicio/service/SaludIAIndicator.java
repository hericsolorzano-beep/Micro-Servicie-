package com.ejemplo.microservicio.service;

import com.ejemplo.microservicio.client.ClienteIAService;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Inyecta la salud del servicio de IA en el endpoint de actuator.
 *
 * Sin esto, /actuator/health responde UP aunque Python este caido: el
 * indicador "ping" solo comprueba que el proceso de Java vive. Eso hace
 * que el balanceador de carga siga mandando trafico a una instancia que
 * no puede atender ninguna peticion.
 *
 * Delegar la comprobacion en ClienteIAService mantiene el transporte
 * HTTP en una sola clase.
 */
@Component("servicioIA")
public class SaludIAIndicator implements HealthIndicator {

    private final ClienteIAService clienteIA;

    public SaludIAIndicator(ClienteIAService clienteIA) {
        this.clienteIA = clienteIA;
    }

    @Override
    public Health health() {
        try {
            return clienteIA.estaDisponible()
                    ? Health.up().withDetail("servicioIA", "operativo").build()
                    : Health.down()
                            .withDetail("servicioIA", "modelos no cargados")
                            .build();

        } catch (Exception e) {
            // Falla la comprobacion de salud sin propagar: un health check
            // que lanza deja el endpoint 500 y se pierde el diagnostico.
            return Health.down(e).build();
        }
    }
}