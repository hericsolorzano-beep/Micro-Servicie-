package com.ejemplo.microservicio.service;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;
import javax.sql.DataSource;

/**
 * Comprueba que la base de datos responde de verdad.
 *
 * Spring Boot ya trae su propio indicador de DataSource, asi que esto es
 * redundante... y aun asi se anade, por una razon concreta: el indicador
 * automatico devuelve UP con un simple "hay un DataSource configurado".
 * Si la base esta Caida pero el pool todavia no ha intentado conectar,
 * el health check dira que todo esta bien y el balanceador seguira
 * mandando trafico.
 *
 * Una consulta real (SELECT 1) obliga al pool a abrir una conexion de
 * verdad. Cuesta unos milisegundos y convierte el health en informacion
 * fiable en lugar de configuracion.
 */
@Component
public class BaseDatosHealthIndicator implements HealthIndicator {

    private final DataSource dataSource;

    public BaseDatosHealthIndicator(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Health health() {
        // Abrir conexion de verdad es lo que hace fiable la comprobacion:
        var inicio = System.nanoTime();

        try (var conexion = dataSource.getConnection();
             var consulta = conexion.createStatement();
             var resultado = consulta.executeQuery("SELECT 1")) {

            boolean ok = resultado.next();
            long ms = (System.nanoTime() - inicio) / 1_000_000;

            if (!ok) {
                return Health.down()
                        .withDetail("error", "SELECT 1 no devolvio filas")
                        .build();
            }

            return Health.up()
                    .withDetail("base", conexion.getMetaData().getDatabaseProductName())
                    .withDetail("version", conexion.getMetaData().getDatabaseProductVersion())
                    .withDetail("consulta_ms", ms)
                    .build();

        } catch (Exception e) {
            // Se registra el tipo, no la traza completa, para no volcar
            // credenciales de la URL de conexion en el health check.
            return Health.down()
                    .withDetail("error", e.getClass().getSimpleName())
                    .build();
        }
    }
}