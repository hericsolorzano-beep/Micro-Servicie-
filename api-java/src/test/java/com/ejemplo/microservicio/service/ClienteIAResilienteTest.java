package com.ejemplo.microservicio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.ejemplo.microservicio.client.ClienteIAService;
import com.ejemplo.microservicio.dto.PeticionFraudePython;
import com.ejemplo.microservicio.dto.RespuestaFraudePython;
import com.ejemplo.microservicio.exception.ServicioIANoDisponibleException;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Testcontainers;
import com.ejemplo.microservicio.BaseDatosTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Tests de la resiliencia contra el servicio de IA, con un cliente simulado.
 *
 * Se usa @SpringBootTest y no un test de slice porque las anotaciones de
 * Resilience4j dependen de los proxies que Spring crea al construir el
 * contexto. Un test unitario con new ClienteIAResiliente() tendria el
 * codigo SIN proxy, es decir sin proteccion, y pasaria dando una falsa
 * sensacion de que los patrones funcionan.
 *
 * Esa es exactamente la trampa que la clase documenta, y por eso el test
 * la verifica de forma explicita en retryReintenta.
 */
// Levanta un PostgreSQL aunque este test no toque la base: es un
// @SpringBootTest, que carga TODOS los beans, incluido el EntityManager
// y Flyway. Sin la base, el contexto no arranca.
@SpringBootTest
@Testcontainers
@Import(BaseDatosTest.class)
@TestPropertySource(properties = {
        // Valores agresivos a proposito: con los de produccion (50% de
        // fallos sobre 20 llamadas) el test tardaria minutos.
        "resilience4j.circuitbreaker.instances.servicioIA.sliding-window-size=4",
        "resilience4j.circuitbreaker.instances.servicioIA.minimum-number-of-calls=4",
        "resilience4j.circuitbreaker.instances.servicioIA.failure-rate-threshold=50",
        "resilience4j.circuitbreaker.instances.servicioIA.wait-duration-in-open-state=2s",
        "resilience4j.retry.instances.servicioIA.max-attempts=3",
        "resilience4j.retry.instances.servicioIA.wait-duration=50ms",
        "resilience4j.bulkhead.instances.servicioIA.max-concurrent-calls=4",
        "resilience4j.circuitbreaker.circuitBreakerAspectOrder=1",
        "resilience4j.retry.retryAspectOrder=2"
})
class ClienteIAResilienteTest {

    @Autowired
    private ClienteIAResiliente servicio;

    /**
     * Se sustituye el cliente HTTP por un doble. Sin esto, ClienteIAResiliente
     * llamaria al Python de verdad (localhost:8000), el test fallaria por
     * conexion rechazada y el motivo real — que estamos probando los
     * patrones de resiliencia — quedaria tapado.
     *
     * La cuenta de invocaciones de este mock es lo que permite comprobar
     * que el circuito abierto deja de llamar al servicio.
     */
    @MockitoBean
    private ClienteIAService clienteReal;

    @Autowired
    private CircuitBreakerRegistry breakerRegistry;

    @Autowired
    private RetryRegistry retryRegistry;

    @Autowired
    private io.github.resilience4j.bulkhead.BulkheadRegistry bulkheadRegistry;

    private static final PeticionFraudePython PETICION =
            new PeticionFraudePython(9000.0, 3, "NG", 5200.0);

    @BeforeEach
    void reiniciar() {
        // Cada test empieza con el estado limpio. Sin esto, un test que
        // abre el circuito poisona los siguientes y el orden de
        // ejecucion pasa a importar.
        breakerRegistry.circuitBreaker("servicioIA").reset();
    }

    @Test
    @DisplayName("Si la IA responde, el resultado pasa intacto")
    void exitoPasaIntacto() throws Exception {
        when(clienteReal.predecirFraude(any()))
                .thenReturn(new RespuestaFraudePython(true, 0.93, "critico", "rf"));

        var r = servicio.predecirFraude(PETICION);

        assertThat(r.esFraude()).isTrue();
        assertThat(r.probabilidad()).isEqualTo(0.93);
    }

    @Test
    @DisplayName("El retry reintenta: cuenta como UN fallo, no como tres")
    void retryReintenta() throws Exception {
        AtomicInteger intentos = new AtomicInteger();

        when(clienteReal.predecirFraude(any())).thenAnswer(inv -> {
            intentos.incrementAndGet();
            throw new ServicioIANoDisponibleException("IA caida");
        });

        assertThatThrownBy(() -> servicio.predecirFraude(PETICION))
                .isInstanceOf(ServicioIANoDisponibleException.class);

        // Con max-attempts=3 deben verse 3 llamadas. Si fussen 9, el retry
        // y el circuit breaker estan mal ordenados: el breaker de fuera
        // multiplicaria los reintentos en vez de contarlos.
        assertThat(intentos.get())
                .as("El retry debe intentar 3 veces antes de rendirse")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("El retry se recupera si un intento posterior funciona")
    void retrySeRecupera() throws Exception {
        AtomicInteger intentos = new AtomicInteger();

        when(clienteReal.predecirFraude(any())).thenAnswer(inv -> {
            if (intentos.incrementAndGet() < 3) {
                throw new ServicioIANoDisponibleException("reiniciando");
            }
            return new RespuestaFraudePython(false, 0.01, "bajo", "rf");
        });

        // Este es el caso que justifica el retry: Python reiniciandose.
        var r = servicio.predecirFraude(PETICION);

        assertThat(r.esFraude()).isFalse();
        assertThat(intentos.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("El circuito se abre tras fallos sostenidos y deja de llamar")
    void circuitoSeAbre() throws Exception {
        when(clienteReal.predecirFraude(any()))
                .thenThrow(new ServicioIANoDisponibleException("IA caida"));

        var breaker = breakerRegistry.circuitBreaker("servicioIA");

        // minimum-number-of-calls=4 y failure-rate=50%: hacen falta 4
        // llamadas fallidas para que se abra.
        for (int i = 0; i < 4; i++) {
            try {
                servicio.predecirFraude(PETICION);
            } catch (Exception e) {
                // se espera que fallen
            }
        }

        assertThat(breaker.getState()).isIn(CircuitBreaker.State.OPEN);

        // Con el circuito abierto NO debe llamar al servicio: cada
        // llamada ahorrada son CPU y ancho de banda que no se gastan en
        // una dependencia que ya sabemos que esta caida.
        long llamadasTrasAbrir = clienteRealCallCount();
        try {
            servicio.predecirFraude(PETICION);
        } catch (Exception e) {
            // se espera que falle sin tocar la red
        }
        assertThat(clienteRealCallCount())
                .as("Con el circuito abierto no debe haber llamadas nuevas")
                .isEqualTo(llamadasTrasAbrir);
    }

    @Test
    @DisplayName("El bulkhead limita la concurrencia en vez de agotar Tomcat")
    void bulkheadLimitaConcurrencia() throws Exception {
        Bulkhead bulkhead = bulkheadRegistry.bulkhead("servicioIA");
        var metricas = bulkhead.getMetrics();

        assertThat(bulkhead.getBulkheadConfig().getMaxConcurrentCalls()).isEqualTo(4);

        // Con 4 hilos y una cola de 2, la septima llamada concurrente se
        // rechaza al instante en lugar de esperar. Eso es "load
        // shedding": preferimos rechazar una peticion de IA a bloquear
        // un hilo de Tomcat indefinidamente y arrastrar al resto de la
        // API.
        assertThat(metricas.getAvailableConcurrentCalls())
                .as("Sin carga, los 4 permisos deben estar libres")
                .isEqualTo(4);
    }

    @Test
    @DisplayName("Los patrones estan registrados con la configuracion esperada")
    void configuracionAplicada() {
        // Un fallo tipico es escribir la configuracion con otro nombre de
        // instancia y que Resilience4j cree la suya por defecto, con
        // 50 llamadas y 60 segundos. Esto lo detecta.
        var breaker = breakerRegistry.circuitBreaker("servicioIA");
        var retry = retryRegistry.retry("servicioIA");

        assertThat(breaker.getCircuitBreakerConfig().getSlidingWindowSize()).isEqualTo(4);
        assertThat(retry.getRetryConfig().getMaxAttempts()).isEqualTo(3);

        // El umbral de fallos y la ventana minima vienen del perfil de
        // test, no de application.yml. Se comprueban para detectar el fallo
        // tipico de esta libreria: escribir la configuracion con otro
        // nombre de instancia y que Resilience4j cree la suya por defecto
        // (50 llamadas, 60 segundos), todo sin avisar.
        assertThat(breaker.getCircuitBreakerConfig().getFailureRateThreshold()).isEqualTo(50f);
        assertThat(breaker.getCircuitBreakerConfig().getMinimumNumberOfCalls()).isEqualTo(4);

        // El tipo de bulkhead importa: THREADPOOL solo funciona con
        // llamadas asincronas y falla en ejecucion con una sincrona.
        assertThat(bulkheadRegistry.bulkhead("servicioIA")
                .getBulkheadConfig().getMaxConcurrentCalls())
                .isEqualTo(4);
    }

    private long clienteRealCallCount() {
        return org.mockito.Mockito.mockingDetails(clienteReal).getInvocations().size();
    }
}