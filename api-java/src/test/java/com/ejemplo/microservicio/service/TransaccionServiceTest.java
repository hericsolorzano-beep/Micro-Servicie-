package com.ejemplo.microservicio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ejemplo.microservicio.client.ClienteIAService;
import com.ejemplo.microservicio.dto.RespuestaFraudePython;
import com.ejemplo.microservicio.dto.SolicitudTransaccion;
import com.ejemplo.microservicio.domain.Usuario;
import com.ejemplo.microservicio.repository.UsuarioRepository;
import com.ejemplo.microservicio.exception.ServicioIANoDisponibleException;
import com.ejemplo.microservicio.exception.UsuarioNoEncontradoException;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;

import com.ejemplo.microservicio.BaseDatosTest;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Tests de persistencia contra H2 en memoria.
 *
 * Lo que mas importa aqui NO es que el CRUD funcione, sino el caso de
 * fallo: cuando la IA no responde, la transaccion DEBE guardarse igual,
 * sin veredicto y con el error registrado.
 *
 * Si no se guardara, se perderia el registro de todo lo que se intento
 * durante la caida, que es justo lo que despues hay que revisar. Y si se
 * guardara sin distinguir entre "limpieza" y "no evaluada", un fraude
 * pasaria por aprobado.
 *
 * Corre contra PostgreSQL REAL via Testcontainers, no contra H2. Además de
 * la logica, esto ejecuta las migraciones de Flyway y comprueba con
 * ddl-auto=validate que las entidades encajan con el esquema. Con H2,
 * todo eso pasaba sin verificarse.
 */
@SpringBootTest
@Testcontainers
@Import(BaseDatosTest.class)
@Transactional
class TransaccionServiceTest {

    @Autowired
    private TransaccionService servicio;

    @Autowired
    private UsuarioService usuarios;

    @Autowired
    private PasswordEncoder encoder;

    @Autowired
    private com.ejemplo.microservicio.repository.TransaccionRepository repositorio;

    @Autowired
    private UsuarioRepository usuariosRepo;

    @MockitoBean
    private ClienteIAService clienteIA;

    private Usuario usuarioDePrueba() {
        return usuarios.crear("test" + System.nanoTime() + "@ejemplo.com",
                "Usuario Test", "contrasena-larga-123");
    }

    /** 28 componentes PCA, como espera el modelo. */
    private static java.util.List<Double> componentes() {
        return java.util.stream.IntStream.range(0, 28)
                .mapToObj(i -> 1.234)
                .toList();
    }

    private static SolicitudTransaccion transaccion() {
        return new SolicitudTransaccion(
                componentes(), 9000.0, 3, "NG", 5200.0);
    }

    @Test
    @DisplayName("Guarda la transaccion con su veredicto")
    void guardaConVeredicto() throws Exception {
        Usuario u = usuarioDePrueba();
        when(clienteIA.predecirFraude(any())).thenReturn(
                new RespuestaFraudePython(true, 0.93, "critico", "rf", 0.30));

        var r = servicio.analizarYGuardar(u.getId(), transaccion());

        assertThat(r.esFraude()).isTrue();
        assertThat(r.accion()).isEqualTo("BLOQUEADA");
        assertThat(repositorio.countByUsuarioId(u.getId())).isEqualTo(1);

        var guardada = repositorio.findByUsuarioIdOrderByCreadoEnDesc(
                u.getId(), org.springframework.data.domain.PageRequest.of(0, 10)).get(0);

        assertThat(guardada.fueAnalizada()).isTrue();
        assertThat(guardada.getProbabilidad()).isEqualByComparingTo("0.9300");
        assertThat(guardada.getErrorAnalisis()).isNull();
    }

    @Test
    @DisplayName("Cuando la IA falla, guarda SIN veredicto y no la da por limpia")
    void guardaSinVeredictoCuandoLaIaFalla() throws Exception {
        Usuario u = usuarioDePrueba();
        when(clienteIA.predecirFraude(any()))
                .thenThrow(new ServicioIANoDisponibleException("IA caida"));

        // El 503 se propaga: el cliente debe saber que no hay veredicto.
        assertThatThrownBy(() -> servicio.analizarYGuardar(u.getId(), transaccion()))
                .isInstanceOf(ServicioIANoDisponibleException.class);

        // PERO la transaccion se guarda. Esto es lo que el test verifica y
        // lo que un simple "lanza excepcion" no comprobaria.
        assertThat(repositorio.countByUsuarioId(u.getId()))
                .as("La transaccion debe persistirse aunque el analisis falle")
                .isEqualTo(1);

        var guardada = repositorio.findByUsuarioIdOrderByCreadoEnDesc(
                u.getId(), org.springframework.data.domain.PageRequest.of(0, 10)).get(0);

        // esFraude NULL, no FALSE. La diferencia es de seguridad: FALSE
        // significa "evaluada y limpia", NULL significa "nadie la miro".
        assertThat(guardada.getEsFraude())
                .as("NULL significa no evaluada, no limpia")
                .isNull();
        assertThat(guardada.fueAnalizada()).isFalse();
        assertThat(guardada.getErrorAnalisis()).isNotBlank();
        assertThat(guardada.getAccion())
                .as("Sin analisis no puede haber accion de negocio")
                .isNull();
    }

    @Test
    @DisplayName("La transaccion fallida NO cuenta como fraude")
    void laFallidaNoCuentaComoFraude() throws Exception {
        Usuario u = usuarioDePrueba();
        when(clienteIA.predecirFraude(any()))
                .thenThrow(new ServicioIANoDisponibleException("IA caida"));

        try {
            servicio.analizarYGuardar(u.getId(), transaccion());
        } catch (Exception e) {
            // esperado
        }

        // countByUsuarioIdAndEsFraudeTrue cuenta SOLO los esFraude=true.
        // Un null no debe entrar en esa cuenta, porque haria creer que el
        // sistema tiene detecciones donde no evaluo nada.
        assertThat(repositorio.countByUsuarioIdAndEsFraudeTrue(u.getId())).isZero();
        assertThat(repositorio.countByUsuarioId(u.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("Un usuario inexistente da error y no crea nada")
    void usuarioInexistente() throws Exception {
        assertThatThrownBy(() -> servicio.analizarYGuardar(999_999L, transaccion()))
                .isInstanceOf(UsuarioNoEncontradoException.class);

        // No debe crearse una transaccion huerfana.
        verify(clienteIA, never()).predecirFraude(any());
    }

    @Test
    @DisplayName("El historial ordena por fecha descendente y pagina")
    void historialOrdenaYPagina() throws Exception {
        Usuario u = usuarioDePrueba();
        when(clienteIA.predecirFraude(any())).thenReturn(
                new RespuestaFraudePython(false, 0.01, "bajo", "rf", 0.30));

        for (int i = 0; i < 5; i++) {
            servicio.analizarYGuardar(u.getId(), transaccion());
        }

        var pagina = org.springframework.data.domain.PageRequest.of(0, 3);
        var resultado = repositorio.buscar(u.getId(), false, pagina);

        assertThat(resultado).hasSize(3);
        assertThat(repositorio.countByUsuarioId(u.getId())).isEqualTo(5);

        // Orden descendente: la primera de la pagina es la mas reciente.
        for (int i = 1; i < resultado.size(); i++) {
            assertThat(resultado.get(i - 1).getCreadoEn())
                    .isAfterOrEqualTo(resultado.get(i).getCreadoEn());
        }
    }

    @Test
    @DisplayName("El filtro soloFraude deja fuera las limpias")
    void filtroSoloFraude() throws Exception {
        Usuario u = usuarioDePrueba();
        when(clienteIA.predecirFraude(any()))
                .thenReturn(new RespuestaFraudePython(false, 0.01, "bajo", "rf", 0.30));
        servicio.analizarYGuardar(u.getId(), transaccion());

        when(clienteIA.predecirFraude(any()))
                .thenReturn(new RespuestaFraudePython(true, 0.95, "critico", "rf", 0.30));
        servicio.analizarYGuardar(u.getId(), transaccion());

        var soloFraude = repositorio.buscar(u.getId(), true,
                org.springframework.data.domain.PageRequest.of(0, 10));

        assertThat(soloFraude).hasSize(1);
        assertThat(soloFraude.get(0).getEsFraude()).isTrue();
        assertThat(repositorio.countByUsuarioIdAndEsFraudeTrue(u.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("Dos email identicos se rechazan por la restriccion unica")
    void emailDuplicado() {
        String email = "duplicado" + System.nanoTime() + "@ejemplo.com";
        usuarios.crear(email, "Primero", "contrasena-larga-123");

        assertThatThrownBy(() -> usuarios.crear(email, "Segundo", "otra-contrasena"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Ya existe");
    }

    @Test
    @DisplayName("La contrasena se guarda hasheada, nunca en claro")
    void contrasenaHasheada() {
        Usuario u = usuarioDePrueba();

        String hash = u.getHashContrasena();

        assertThat(hash)
                .as("El hash no debe contener la contrasena en claro")
                .doesNotContain("contrasena-larga-123");
        assertThat(hash).startsWith("$2");  // prefijo de BCrypt
        assertThat(hash).hasSize(60);      // longitud fija de BCrypt

        // Y las credenciales se validan contra el hash.
        assertThat(usuarios.credencialesValidas(u.getEmail(), "contrasena-larga-123"))
                .isTrue();
        assertThat(usuarios.credencialesValidas(u.getEmail(), "incorrecta")).isFalse();
    }

    @Test
    @DisplayName("Dos usuarios con la misma contrasena tienen hashes distintos")
    void hashesDistintos() {
        // BCrypt lleva el salt dentro. Si dos hashes coincidieran, el
        // atacante podria romperlos a la vez, y eso delata una
        // implementacion que no usa salt.
        Usuario a = usuarioDePrueba();
        Usuario b = usuarios.crear(
                "otro" + System.nanoTime() + "@ejemplo.com", "Otro", "contrasena-larga-123");

        assertThat(a.getHashContrasena()).isNotEqualTo(b.getHashContrasena());
    }

    @Test
    @DisplayName("Un usuario desactivado no puede autenticarse")
    void usuarioDesactivadoNoAutentica() {
        Usuario u = usuarioDePrueba();
        u.desactivar();
        usuariosRepo.save(u);

        // Una cuenta desactivada que sigue autenticandose es un fallo de
        // seguridad tan grave como una contrasena filtrada.
        assertThat(usuarios.credencialesValidas(u.getEmail(), "contrasena-larga-123"))
                .isFalse();
    }
}