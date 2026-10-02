package com.ejemplo.microservicio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.ejemplo.microservicio.BaseDatosTest;
import com.ejemplo.microservicio.client.ClienteIAService;
import com.ejemplo.microservicio.dto.PeticionFraudePython;
import com.ejemplo.microservicio.dto.RespuestaFraudePython;
import com.ejemplo.microservicio.exception.PeticionRechazadaPorIAException;
import com.ejemplo.microservicio.exception.ServicioIANoDisponibleException;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * La saturacion local NO puede apagar la deteccion de fraude.
 *
 * Este archivo existe por un fallo que solo aparecio al medir. El
 * circuito y el bulkhead estaban bien configurados por separado y
 * juntos se anulaban:
 *
 *   - el bulkhead limita a 10 llamadas simultaneas a Python
 *   - el circuito abre tras un 50% de fallos
 *
 * Pero el orden por defecto de los aspectos de Resilience4j pone el
 * bulkhead el ULTIMO. Asi que el circuito ve los rechazos del propio
 * bulkhead y los cuenta como fallos de la dependencia.
 *
 * Medido contra el stack con 20 peticiones simultaneas:
 *
 *   tanda 1: 10 correctas, 11 SERVICIO_SATURADO, 4 CIRCUITO_ABIERTO
 *   tanda 2: 25 CIRCUITO_ABIERTO
 *   y despues, EN SECUCIAL, con Python sano:
 *   CIRCUITO_ABIERTO, CIRCUITO_ABIERTO, CIRCUITO_ABIERTO...
 *
 * Quince peticiones de cargaModerada dejaban el sistema entero sin
 * deteccion de fraude durante los 30 s de wait-duration-in-open-state,
 * sin que hubiera fallado ni una sola llamada a Python. Y lo peor no es
 * que se disparara por un fallo: se dispara con trafico normal, que es
 * justo cuando el detector hace falta.
 *
 * El arreglo es bulkheadAspectOrder: 0, para que el bulkhead rechace
 * antes de que exista el circuito.
 */
@SpringBootTest
@Testcontainers
@Import(BaseDatosTest.class)
class SaturacionNoAbreElCircuitoTest {

    @MockitoBean
    private ClienteIAService clienteIA;

    @Autowired
    private ClienteIAResiliente resiliente;

    @Autowired
    private io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry breakers;

    @Autowired
    private io.github.resilience4j.bulkhead.BulkheadRegistry bulkheads;

    @Autowired
    private org.springframework.core.env.Environment entorno;

    /**
     * Cuanto "tarda" Python en el test.
     *
     * 50 ms basta para que 30 hilos en vuelo superen un bulkhead de 4 (que
     * es el limite del yml de test) y mantiene el test en unos segundos.
     */
    private static final int RETARDO_SIMULADO_MS = 50;

    private PeticionFraudePython peticion() {
        // 28 componentes: el numero que el esquema exige.
        return new PeticionFraudePython(
                java.util.Collections.nCopies(28, 1.0),
                100.0, 12, "ES", 50.0);
    }

    private RespuestaFraudePython respuestaCorrecta() {
        // Los cinco campos del record: el quinto es el umbral.
        return new RespuestaFraudePython(false, 0.02, "bajo", "rf", 0.3);
    }

    /**
     * Las excepciones que el circuito NO cuenta como fallo, tal como
     * quedaron resueltas por Spring.
     *
     * Se leen con Environment e indice porque @Value no sirve para listas
     * YAML: llegan como claves indexadas y el binding a List<String> las
     * concatena vacio. Se comprobo: con @Value la lista llegaba [].
     */
    private java.util.List<String> excepcionesIgnoradas() {
        var encontradas = new java.util.ArrayList<String>();
        String prefijo = "resilience4j.circuitbreaker.instances.servicioIA"
                + ".ignore-exceptions";
        for (int i = 0; i < 20; i++) {
            String valor = entorno.getProperty(prefijo + "[" + i + "]");
            if (valor == null) {
                break;
            }
            encontradas.add(valor);
        }
        return encontradas;
    }

    @Test
    @DisplayName("El circuito ignora tanto el 4xx como el rechazo del bulkhead")
    void elCircuitoIgnoraLoQueNoEsCaidaDeLaDependencia() {
        // Estas dos lineas son TODO el arreglo, asi que se comprueba que
        // estan. El fallo que las motivaba lo en el test de abajo.
        assertThat(excepcionesIgnoradas())
                .as("un 4xx de Python no es una caida")
                .contains(PeticionRechazadaPorIAException.class.getName());

        assertThat(excepcionesIgnoradas())
                .as("el rechazo del propio bulkhead tampoco: es saturacion "
                    + "local, no una caida de la dependencia. Sin esta linea, "
                    + "quince peticiones de trafico normal apagaban el "
                    + "detector de fraude del sistema entero")
                .contains(BulkheadFullException.class.getName());
    }

    @Test
    @DisplayName("Saturar el bulkhead deja el circuito cerrado y el sistema vivo")
    void saturarNoAbreElCircuito() throws Exception {

        breakers.circuitBreaker("servicioIA").reset();

        // Python responde bien, pero TARDANDO, como el de verdad (~350 ms).
        //
        // El retardo es lo que hace el test real. Con el mock devolviendo
        // al instante, las 30 llamadas se completan una detras de otra y
        // nunca hay mas de 10 en vuelo, asi que el bulkhead no rechaza
        // nada y el test pasaria sin comprobar nada. Ya ocurrio: la
        // primera version fallaba con "debe haber rechazos", porque
        // estaba midiendo una saturacion que no existia.
        when(clienteIA.predecirFraude(any())).thenAnswer(inv -> {
            Thread.sleep(RETARDO_SIMULADO_MS);
            return respuestaCorrecta();
        });

        int limite = bulkheads.bulkhead("servicioIA").getBulkheadConfig()
                .getMaxConcurrentCalls();
        int exceso = limite * 3;

        AtomicInteger aceptadas = new AtomicInteger();
        AtomicInteger rechazadas = new AtomicInteger();
        AtomicInteger circuitoAbierto = new AtomicInteger();
        AtomicInteger otroError = new AtomicInteger();

        CountDownLatch salida = new CountDownLatch(exceso);
        ExecutorService pool = Executors.newFixedThreadPool(exceso);

        for (int i = 0; i < exceso; i++) {
            pool.submit(() -> {
                try {
                    resiliente.predecirFraude(peticion());
                    aceptadas.incrementAndGet();
                } catch (BulkheadFullException e) {
                    // Lo esperado: la saturacion se resuelve en local.
                    rechazadas.incrementAndGet();
                } catch (io.github.resilience4j.circuitbreaker
                        .CallNotPermittedException e) {
                    // Lo que NO deberia pasar.
                    circuitoAbierto.incrementAndGet();
                } catch (Exception e) {
                    otroError.incrementAndGet();
                } finally {
                    salida.countDown();
                }
            });
        }

        assertThat(salida.await(30, TimeUnit.SECONDS))
                .as("las peticiones deberian terminar")
                .isTrue();
        pool.shutdownNow();

        // Hubo saturacion de verdad: si no, el test no probaria nada.
        assertThat(rechazadas.get())
                .as("con el triple de peticiones simultaneas tiene que "
                        + "haber rechazos del bulkhead")
                .isPositive();
        assertThat(aceptadas.get())
                .as("y tambien peticiones atendidas: el sistema sigue vivo")
                .isPositive();

        // Y aqui esta el fallo que se quiere cazar.
        assertThat(circuitoAbierto.get())
                .as("el bulkhead no debe abrir el circuito: la saturacion "
                        + "local no es una caida de la dependencia")
                .isZero();
        assertThat(otroError.get())
                .as("no debe haber otros errores")
                .isZero();

        assertThat(breakers.circuitBreaker("servicioIA").getState())
                .as("tras saturar, el circuito sigue CERRADO")
                .isEqualTo(io.github.resilience4j.circuitbreaker
                        .CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("Y despues de saturar, una peticion normal se sigue atendiendo")
    void despuesDeSaturarSigueAtendiendo() throws Exception {

        breakers.circuitBreaker("servicioIA").reset();
        AtomicInteger enVuelo = new AtomicInteger();
        AtomicInteger maximoEnVuelo = new AtomicInteger();
        when(clienteIA.predecirFraude(any())).thenAnswer(inv -> {
            int ahora = enVuelo.incrementAndGet();
            maximoEnVuelo.accumulateAndGet(ahora, Math::max);
            try {
                Thread.sleep(RETARDO_SIMULADO_MS);
                return respuestaCorrecta();
            } finally {
                enVuelo.decrementAndGet();
            }
        });

        int limite = bulkheads.bulkhead("servicioIA").getBulkheadConfig()
                .getMaxConcurrentCalls();

        // Rafaga que satura, y al terminar, una peticion solitaria.
        CountDownLatch salida = new CountDownLatch(limite * 3);
        ExecutorService pool = Executors.newFixedThreadPool(limite * 3);
        for (int i = 0; i < limite * 3; i++) {
            pool.submit(() -> {
                try {
                    resiliente.predecirFraude(peticion());
                } catch (Exception ignora) {
                    // Da igual: aqui solo importa la peticion de despues.
                } finally {
                    salida.countDown();
                }
            });
        }
        salida.await(30, TimeUnit.SECONDS);
        pool.shutdownNow();

        // El tope de llamadas EN VUELO es el limite del bulkhead, nunca mas:
        // eso es lo que significa el limite. Si el maximo observado fuera
        // MENOR, el bulkhead estaria infrautilizado y no hubo saturacion
        // real, que es lo que hay que descartar.
        assertThat(maximoEnVuelo.get())
                .as("la rafaga tiene que llenar el bulkhead (%d en vuelo); "
                    + "si nunca se alcanza el tope, no hubo saturacion y el "
                    + "test no prueba nada. Maximo observado: %d",
                    limite, maximoEnVuelo.get())
                .isEqualTo(limite);

        // Este es el sintoma que se vio en ejecucion: tras la rafaga, las
        // peticiones SEQUENCIALES devolvian CIRCUITO_ABIERTO con Python
        // perfectamente sano.
        var respuesta = resiliente.predecirFraude(peticion());

        assertThat(respuesta.esFraude())
                .as("una peticion normal tras la saturacion debe atenderse; "
                        + "antes devolvia CIRCUITO_ABIERTO")
                .isFalse();
    }

    @Test
    @DisplayName("Una caida REAL de Python si abre el circuito")
    void laCaidaRealSiAbreElCircuito() throws Exception {
        // La contraparte: si el bulkhead por si solo ya abriera el
        // circuito, este test pasaria y el sistema no protegeria de nada.
        breakers.circuitBreaker("servicioIA").reset();

        when(clienteIA.predecirFraude(any()))
                .thenThrow(new ServicioIANoDisponibleException("Python caido"));

        for (int i = 0; i < 12; i++) {
            try {
                resiliente.predecirFraude(peticion());
            } catch (Exception ignora) {
                // Se espera el fallo.
            }
        }

        assertThat(breakers.circuitBreaker("servicioIA").getState())
                .as("una caida real de la dependencia si debe abrir el circuito")
                .isEqualTo(io.github.resilience4j.circuitbreaker
                        .CircuitBreaker.State.OPEN);
    }
}
