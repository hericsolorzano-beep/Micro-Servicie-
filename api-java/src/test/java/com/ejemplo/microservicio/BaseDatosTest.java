package com.ejemplo.microservicio;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * PostgreSQL real para los tests de persistencia.
 *
 * Se sustituyo H2 por esto. Con H2, los tests creaban las tablas desde el
 * modelo de JPA y NUNCA ejecutaban las migraciones de Flyway: el esquema
 * de produccion no lo verificaba nadie. Un error de sintaxis o un tipo
 * incompatible en V1__esquema_inicial.sql habria llegado hasta el
 * despliegue, y ddl-auto=validate solo lo detecta al arrancar, en
 * produccion.
 *
 * Testcontainers levanta un PostgreSQL de verdad en cada ejecucion:
 *
 *   - Flyway aplica las migraciones -> se prueba el SQL real
 *   - ddl-auto=validate compara entidades y esquema -> se prueba el
 *     mapeo
 *   - Se ejecutan los CHECK, tipos y colaciones de PostgreSQL, no los
 *     aproximados de H2
 *
 * @ServiceConnection conecta Spring al contenedor automaticamente, sin
 * escribir la URL a mano ni depender de un puerto fijo.
 *
 * REQUIERE DOCKER. Si no esta disponible, los tests de persistencia
 * fallan con un mensaje explicito. Es deliberado: un test que se salta en
 * silencio por falta de Docker daria la misma senal de seguridad que uno
 * que pasa, y no se distingue.
 *
 * Para correrlos el usuario debe poder hablar con el socket de Docker.
 * Con `sudo usermod -aG docker $USER` y una sesion nueva, `mvn test`
 * funciona directamente. Sin recargar la sesion, hay que ejecutar:
 *   sg docker -c 'mvn test'
 */
@TestConfiguration(proxyBeanMethods = false)
public class BaseDatosTest {

    /**
     * La misma version del compose, para que los tests y el desarrollo
     * usen el mismo motor. Si divergen, un test puede pasar con una
     * version y fallar con la otra.
     */
    private static final DockerImageName IMAGEN =
            DockerImageName.parse("postgres:17-alpine");

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> contenedorPostgres() {
        return new PostgreSQLContainer<>(IMAGEN)
                // Nombre de la base de test. El de por defecto ("test")
                // sirve, pero nombrarlo hace la intencion obvia cuando se
                // mira una traza de error.
                .withDatabaseName("microservicio_test")
                .withUsername("test")
                .withPassword("test")
                // Sin esto, el contenedor se queda levantado entre
                // ejecuciones y la base acumula datos de
                // pruebas anteriores.
                .withReuse(false);
    }
}