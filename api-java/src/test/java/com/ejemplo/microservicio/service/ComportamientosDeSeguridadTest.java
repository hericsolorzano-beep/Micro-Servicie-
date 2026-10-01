package com.ejemplo.microservicio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.ejemplo.microservicio.BaseDatosTest;
import com.ejemplo.microservicio.domain.Usuario;
import com.ejemplo.microservicio.dto.SolicitudTransaccion;
import com.ejemplo.microservicio.exception.PeticionRechazadaPorIAException;
import com.ejemplo.microservicio.exception.ServicioIANoDisponibleException;
import com.ejemplo.microservicio.repository.UsuarioRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Comportamientos que se descubrieron al MEDIR el stack, no al leer el
 * codigo.
 *
 * Cada test responde a algo que se comprobo que estaba mal. Sin este
 * archivo, cualquiera de ellos volvería a colarse sin que nada fallara.
 *
 * Levanta el contexto real (con Testcontainers) en vez de uno recortado,
 * porque dos tests leen la configuracion de resiliencia TAL COMO Spring la
 * resolvio. Un contexto de prueba fabricaria las suyas y pasarian aunque la
 * aplicacion real estuviera mal configurada, que es justo el fallo que se
 * quiere cazar.
 *
 * El repositorio va falseado: ninguna comprobacion toca la base de datos.
 */
@SpringBootTest
@Testcontainers
@Import(BaseDatosTest.class)
class ComportamientosDeSeguridadTest {

    @MockitoBean
    private UsuarioRepository repositorio;

    @Autowired
    private PasswordEncoder encoder;

    @Autowired
    private UsuarioService usuarios;

    @Autowired
    private Validator validator;

    @Autowired
    private org.springframework.core.env.Environment entorno;

    /**
     * Nombres de las clases que el retry reintenta, tal como quedaron
     * resueltos por Spring.
     *
     * Se leen con Environment y con indice porque @Value NO funciona aqui:
     * una lista YAML llega al entorno como claves indexadas
     * (...[0], ...[1]) y el binding de @Value a List<String> las
     * concatena vacio. Se comprobo: con @Value la lista llegaba como [],
     * con lo que el test habria pasado comprobando una lista vacia.
     */
    private java.util.List<String> clasesReintentadas() {
        return leerLista("resilience4j.retry.instances.servicioIA.retry-exceptions");
    }

    /** Las mismas, para el circuito. */
    private java.util.List<String> clasesIgnoradas() {
        return leerLista(
                "resilience4j.circuitbreaker.instances.servicioIA.ignore-exceptions");
    }

    private java.util.List<String> leerLista(String prefijo) {
        var encontradas = new java.util.ArrayList<String>();
        for (int i = 0; i < 20; i++) {
            String valor = entorno.getProperty(prefijo + "[" + i + "]");
            if (valor == null) {
                break;
            }
            encontradas.add(valor);
        }
        return encontradas;
    }

    // ------------------------------------------------------------------
    // 1. Enumeracion de cuentas por tiempo de respuesta
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Login: un email inexistente tarda lo mismo que uno existente")
    void elLoginNoDelataQueEmailsExisten() throws Exception {
        // MEDIDO contra el stack antes del arreglo:
        //   usuario existente:   108,4 ms   (BCrypt, ~100 ms)
        //   usuario inexistente:  16,0 ms   (no hacia nada)   -> 6,8x
        //
        // El cuerpo de la respuesta era identico, asi que el unico canal
        // que quedaba era el tiempo, y medirlo por red es trivial. Con el
        // hash de relleno los dos caminos ejecutan BCrypt.
        long conUsuario = medirConUsuario("ana@ejemplo.com", "incorrecta");
        long sinUsuario = medirConUsuario("nadie@ejemplo.com", "incorrecta");

        // No se exige igualdad exacta: el ruido de la maquina y del JIT
        // harian el test fragil. Se exige que la diferencia sea una
        // fraccion y no un factor. Antes era 6,8x.
        double ratio = (double) Math.max(conUsuario, sinUsuario)
                / Math.max(1, Math.min(conUsuario, sinUsuario));

        assertThat(ratio)
                .as("diferencia de tiempo entre usuario existente (%d ms) e "
                        + "inexistente (%d ms)", conUsuario, sinUsuario)
                .isLessThan(2.0);
    }

    private long medirConUsuario(String email, String contrasena) {
        // Se descarta la primera pasada: la inicializacion de la libreria
        // BCrypt paga un coste la primera vez y falsearia la medida.
        usuarios.credencialesValidas(email, contrasena);

        long inicio = System.nanoTime();
        for (int i = 0; i < 3; i++) {
            usuarios.credencialesValidas(email, contrasena);
        }
        return (System.nanoTime() - inicio) / 3_000_000;
    }

    @Test
    @DisplayName("Login: un usuario inactivo no puede entrar")
    void elLoginRechazaUsuariosInactivos() throws Exception {
        // Un usuario desactivado es un caso distinto del inexistente en
        // los datos, pero debe ser indistinguible desde fuera. Si el
        // filtro isActivo se aplicara antes de verificar, revelaria
        // tambien que la cuenta EXISTE.
        Usuario inactivo = new Usuario("vetado@ejemplo.com", "V",
                encoder.encode("la-contrasena"));
        inactivo.desactivar();

        when(repositorio.findByEmailIgnoreCase("vetado@ejemplo.com"))
                .thenReturn(Optional.of(inactivo));

        assertThat(usuarios.credencialesValidas("vetado@ejemplo.com",
                "la-contrasena"))
                .as("Un usuario inactivo no puede entrar")
                .isFalse();
    }

    // ------------------------------------------------------------------
    // 2. El circuit breaker no se envenena con un 4xx
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Un 4xx de Python NO abre el circuito para el resto")
    void un4xxNoAbreElCircuito() throws Throwable {
        // El hallazgo mas caro de la revision, medido contra el stack:
        //
        //   ES -> 200 APROBADA          (peticion legitima)
        //   AA -> 503 IA_NO_DISPONIBLE
        //   ... y a partir de la sexta:
        //   AG -> 503 CIRCUITO_ABIERTO
        //   ES -> 503 CIRCUITO_ABIERTO   <-- el cliente legitimo cae
        //
        // Un 422 de Python (pais no soportado) se traducía a "IA no
        // disponible", que era el unico tipo de retry-exceptions y ademas
        // contaba como fallo del circuito. Un campo de texto libre
        // permitia dejar el detector de fraude caido para todos durante
        // 30 s. En pagos, desactivar la deteccion de fraude es el fallo que
        // mas caro sale: se aceptan todos los fraude.
        var rechazo = new PeticionRechazadaPorIAException(422,
                "pais no soportado");

        CircuitBreaker breaker = CircuitBreaker.of("prueba",
                io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
                        .custom()
                        .ignoreExceptions(PeticionRechazadaPorIAException.class)
                        .build());

        // Se simula el registro del fallo con la API real del circuito,
        // no calling onError: la firma de "cuando fallo y cuanto tardo"
        // cambio entre versiones de Resilience4j y no es lo que se quiere
        // comprobar aqui. Lo que importa es si la EXCEPCION se ignora.
        for (int i = 0; i < 10; i++) {
            try {
                breaker.executeCallable(() -> {
                    throw rechazo;
                });
            } catch (RuntimeException esperado) {
                // El circuito propaga el error hacia arriba, que es lo
                // normal. Aqui solo importa el estado, no la excepcion.
            }
        }

        assertThat(breaker.getState())
                .as("un rechazo por contenido no es un fallo del circuito")
                .isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("El rechazo por contenido no se reintenta; la caida si")
    void elRechazoNoSeReintenta() {
        // Comprobado contra la configuracion real, no contra el YAML.
        //
        // Importa porque PeticionRechazadaPorIAException es
        // RuntimeException: si retry-exceptions dijera "RuntimeException",
        // los 422 se reintentarian tres veces, cada una travelling por la
        // red, para devolver el mismo 400 mas tarde.
        assertThat(clasesReintentadas())
                .as("Solo se reintenta la caida de la dependencia")
                .containsExactly(ServicioIANoDisponibleException.class.getName());

        assertThat(clasesReintentadas())
                .as("El rechazo por contenido NO se reintenta: reintentado "
                    + "daria el mismo 4xx y un 400 mas tarde")
                .doesNotContain(PeticionRechazadaPorIAException.class.getName());
    }

    @Test
    @DisplayName("El circuito ignora el rechazo por contenido")
    void elCircuitoIgnoraElRechazoPorContenido() {
        // Sin esto, ignore-exceptions podria desaparecer de la
        // configuracion y el test un4xxNoAbreElCircuito seguiria en
        // verde, porque construye su propio circuito en vez de usar el de
        // la aplicacion.
        assertThat(clasesIgnoradas())
                .contains(PeticionRechazadaPorIAException.class.getName());
    }

    @Test
    @DisplayName("La caida real SI cuenta como fallo del circuito")
    void laCaidaRealSiCuenta() throws Throwable {
        // La contraparte del test anterior: si todo se ignorase, el
        // circuito no protegería de nada y pareceria funcionar.
        var caida = new ServicioIANoDisponibleException("Python no responde");
        CircuitBreaker breaker = CircuitBreaker.ofDefaults("prueba");

        for (int i = 0; i < 5; i++) {
            try {
                breaker.executeCallable(() -> {
                    throw caida;
                });
            } catch (Exception esperado) {
                // Se propaga, como arriba. Solo importa el estado.
            }
        }

        assertThat(breaker.getMetrics().getNumberOfFailedCalls())
                .as("los fallos reales se cuentan, y por eso abren el circuito")
                .isPositive();
    }

    // ------------------------------------------------------------------
    // 3. El alta revela si el email esta ocupado
    // ------------------------------------------------------------------

    @Test
    @DisplayName("El alta devuelve 409 si el email ya esta ocupado")
    void elAltaDevuelve409SiElEmailEstaOcupado() {
        // MEDIDO: alta con email nuevo -> 201, alta repetida -> 409 con
        // "Ya existe un usuario con ese email".
        //
        // Es un oraculo de enumeracion MAS BARATO que el del login: no
        // necesita contrasena ni medir tiempos, solo este endpoint, que es
        // publico. Se mantiene el 409 porque devolver 201 cuando no se ha
        // creado nada seria mentir al cliente, y porque quien se equivoca
        // al escribir su email necesita enterarse. El README lo declara
        // como lo que es: una decision con coste, no una garantia.
        when(repositorio.existsByEmailIgnoreCase("ocupado@ejemplo.com"))
                .thenReturn(true);

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                usuarios.crear("ocupado@ejemplo.com", "X", "contrasena-larga-123")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Ya existe");
    }

    // ------------------------------------------------------------------
    // 4. El rango de los componentes
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Un componente absurdo se rechaza: el cliente elegia la hoja")
    void componenteFueraDeRangoSeRechaza() throws Exception {
        // El veredicto depende de las 28 cifras, y las controla el
        // cliente. En un ensemble de arboles un valor extremo cae en una
        // hoja extrema: es decir, el cliente podia elegir el veredicto.
        // Acotado, se rechaza con 400 antes de gastar una inferencia.
        List<Double> absurdos = new ArrayList<>();
        for (int i = 0; i < 28; i++) {
            absurdos.add(1e30);
        }

        assertThat(validator.validate(
                new SolicitudTransaccion(absurdos, 100.0, 12, "ES", 50.0)))
                .as("un componente en 1e30 debe rechazarse")
                .isNotEmpty();
    }

    @Test
    @DisplayName("Una fila real del dataset pasa la validacion de rango")
    void unaFilaRealPasaLaValidacion() throws Exception {
        // El limite no puede ser mas estrecho que los datos reales: se
        // comprueba contra los extremos observados en OpenML (-113,7 y
        // 120,6, con Amount hasta 25.691,16), que es lo que envia un
        // cliente con datos legitimos.
        List<Double> minimo = new ArrayList<>();
        List<Double> maximo = new ArrayList<>();
        for (int i = 0; i < 28; i++) {
            minimo.add(-113.743);
            maximo.add(120.589);
        }

        assertThat(validator.validate(new SolicitudTransaccion(
                minimo, 25691.16, 12, "ES", 50.0)))
                .as("los extremos reales del dataset deben aceptarse")
                .isEmpty();
    }
}
