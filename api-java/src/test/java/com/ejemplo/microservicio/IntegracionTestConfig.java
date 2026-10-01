package com.ejemplo.microservicio;

import java.time.Duration;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * Levanta PostgreSQL y el servicio de Python REALES para los tests de
 * integracion, y cablea Spring contra ellos.
 *
 * POR QUE ESTO Y NO UN DOBLE
 *
 * Los tests de ClienteIAService usan MockRestServiceServer, que serializa y
 * deserializa con Jackson de verdad pero NUNCA cruza la red. Eso verifica
 * que Java envia lo que cree enviar, no que Python lo entienda.
 *
 * El fallo que escapa de ese hueco tiene forma concreta. Hoy Java manda
 * "distancia_km" y Python espera "distancia_km", y todo va bien. Pero si
 * alguien renombra un campo en Python, el record de Java se deserializa con
 * null (por @JsonIgnoreProperties, que no lanza), el NPE aflora como un 500
 * sin explicacion, y los tests unitarios NO lo detectan. Este contenedor
 * sube la MISMA imagen que produccion y comprueba el contrato de verdad.
 *
 * POR QUE ApplicationContextInitializer Y NO @Bean
 *
 * @DynamicPropertySource solo admite UN parametro (el registro), asi que no
 * puede recibir contenedores por inyeccion. Y un @Bean que dependa del
 * contenedor no garantiza ejecutarse ANTES que el bean que lee la propiedad:
 * Spring instancia los singletons en un orden que no controlamos, y si
 * ConfiguracionClienteIA se crea primero, leeria una URL vacia.
 *
 * Un ApplicationContextInitializer se ejecuta ANTES de que exista el
 * contexto. Ahi se arrancan los contenedores y se inyectan las propiedades
 * ya resueltas, sin carrera posible. Es el patron que Testcontainers
 * documenta para propiedades propias.
 */
public class IntegracionTestConfig implements
        ApplicationContextInitializer<GenericApplicationContext> {

    private static final DockerImageName POSTGRES =
            DockerImageName.parse("postgres:17-alpine");

    /** Etiqueta de la imagen de IA que construye ia-python/Dockerfile.test. */
    private static final DockerImageName IA =
            DockerImageName.parse("microservicio-ia-python:test");

    private static final PostgreSQLContainer<?> POSTGRES_CONT =
            new PostgreSQLContainer<>(POSTGRES)
                    .withDatabaseName("integracion")
                    .withUsername("test")
                    .withPassword("test");

    /**
     * El servicio de IA se construye con los modelos ya entrenados dentro
     * de la imagen. IA_TRANSFORMER=0 desactiva el transformer: aqui no se
     * verifica la calidad del modelo (de eso se ocupa test_motor.py), sino
     * el contrato HTTP. Con el transformer tardaria ~16s en arrancar y
     * ocuparia ~660MB por ejecucion.
     */
    private static final GenericContainer<?> IA_CONT = new GenericContainer<>(IA)
            .withExposedPorts(8000)
            .withEnv("IA_TRANSFORMER", "0")
            .waitingFor(Wait.forHttp("/health")
                    .forPort(8000)
                    .forStatusCode(200)
                    .withStartupTimeout(Duration.ofSeconds(120)))
            .withStartupTimeout(Duration.ofSeconds(150));

    @Override
    public void initialize(GenericApplicationContext contexto) {

        // Arranco ANTES de tocar el contexto. Si esto falla, es aqui donde
        // falla el test, y con un mensaje sobre Docker o la imagen, no con
        // un error de property en algun sitio mas adentro.
        POSTGRES_CONT.start();
        IA_CONT.start();

        String url = POSTGRES_CONT.getJdbcUrl();

        String urlIA = "http://" + IA_CONT.getHost()
                + ":" + IA_CONT.getMappedPort(8000);

        // addInlinedPropertiesToEnvironment acepta pares "clave=valor" en
        // varargs, NO un java.util.Properties: es la firma que tiene, y
        // pasar un Properties no compila.
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(
                contexto,
                "spring.datasource.url=" + url,
                "spring.datasource.username=" + POSTGRES_CONT.getUsername(),
                "spring.datasource.password=" + POSTGRES_CONT.getPassword(),
                // "ia.base-url" es nuestra, no una propiedad estandar, asi
                // que @ServiceConnection no puede cubrirla. Al registrarla
                // aqui, el test usa EXACTAMENTE el cableado de
                // ConfiguracionClienteIA que usa produccion.
                "ia.base-url=" + urlIA);
    }
}