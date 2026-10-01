package com.ejemplo.microservicio.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ejemplo.microservicio.config.ConfiguracionJwt;
import com.ejemplo.microservicio.domain.Usuario;
import com.ejemplo.microservicio.security.ComprobadorDePropiedad;
import com.ejemplo.microservicio.security.ConfiguracionSeguridad;
import com.ejemplo.microservicio.security.EmisorDeToken;
import com.ejemplo.microservicio.service.TransaccionService;
import com.ejemplo.microservicio.service.UsuarioService;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Tests del contrato de /api/v1 y de la comprobacion de propiedad.
 *
 * Dos cosas en una clase porque van juntas: el recurso es
 * /usuarios/{id}/..., y a quien pertenece se decide en la misma linea que
 * se responde. Separarlas haria que el test de la ruta pasara sin
 * comprobar de quien es.
 *
 * ComprobadorDePropiedad se importa REAL, no se falsea. Es una clase sin
 * dependencias que solo lee el token del contexto de seguridad, asi que
 * no cuesta mas, y falsearla haria que estos tests no probaran la
 * autorizacion que dicen probar. El token lo emite el EmisorDeToken real
 * contra la misma clave que verifica el JwtDecoder real: si el subject
 * no fuera el id del usuario, estos tests fallarian.
 *
 * @WebMvcTest se acota a UsuarioController: sin `controllers` cargaria
 * todos, y SesionController necesita UsuarioService (ya falseado) pero
 * tambien cosas que no estan en un slice web.
 */
@WebMvcTest(controllers = UsuarioController.class)
@Import({ConfiguracionSeguridad.class, ConfiguracionJwt.class,
        EmisorDeToken.class, ComprobadorDePropiedad.class})
@TestPropertySource(properties =
        "jwt.secret=clave-de-prueba-larga-suficiente-1234567890")
class UsuarioControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EmisorDeToken emisor;

    @MockitoBean
    private UsuarioService usuarios;

    @MockitoBean
    private TransaccionService transacciones;

    /** Token de un usuario concreto, para las pruebas de propiedad. */
    private RequestPostProcessor como(long usuarioId) {
        return peticion -> {
            peticion.addHeader("Authorization",
                    "Bearer " + emisor.emitir(usuarioId, "ana@ejemplo.com"));
            return peticion;
        };
    }

    private static final String TRANSACCION_VALIDA = """
            {"componentes":[%s],"monto":120.50,"hora":12,"pais":"ES",\
            "distancia_km":80.0}""";

    private static String componentes(int n) {
        return IntStream.range(0, n)
                .mapToObj(i -> "1.234")
                .collect(Collectors.joining(","));
    }

    private void usuarioExistente(Long id) {
        // activo=true es obligatorio: exigir() comprueba el estado de la
        // cuenta, asi que un Usuario por defecto ya serviria, pero se deja
        // explicito para que un fallo del check sea legible.
        Usuario u = new Usuario("ana@ejemplo.com", "Ana", "hash");
        Mockito.when(usuarios.buscarPorId(id)).thenReturn(u);
    }

    // ------------------------------------------------------------------
    // Contrato
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Analizar transaccion devuelve el veredicto con su umbral")
    void analizarDevuelveVeredicto() throws Exception {
        usuarioExistente(1L);
        Mockito.when(transacciones.analizarYGuardar(
                        ArgumentMatchers.anyLong(),
                        ArgumentMatchers.any()))
                .thenReturn(new com.ejemplo.microservicio.dto.RespuestaFraude(
                        false, 0.02, "bajo", "random_forest_fraude_datos_reales",
                        "APROBADA", 45L, 0.30, "componentes_PCA"));

        mockMvc.perform(post("/api/v1/usuarios/1/transacciones")
                        .with(como(1L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(TRANSACCION_VALIDA.formatted(componentes(28))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accion").value("APROBADA"))
                // El umbral viaja al cliente: puede saber si la decision
                // fue conservadora sin conocer la configuracion interna.
                .andExpect(jsonPath("$.umbral").value(0.30))
                // Y se declara que senas se usaron, porque el contrato
                // incluye pais y distancia pero el modelo no los mira.
                .andExpect(jsonPath("$.senas_analizadas").value("componentes_PCA"));
    }

    @Test
    @DisplayName("Numero de componentes incorrecto devuelve 400")
    void componentesIncorrectosDa400() throws Exception {
        usuarioExistente(1L);

        // 20 en vez de 28. El esquema lo rechaza antes de gastar una
        // inferencia: el modelo exigira 28 y fallaria con un error interno.
        mockMvc.perform(post("/api/v1/usuarios/1/transacciones")
                        .with(como(1L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(TRANSACCION_VALIDA.formatted(componentes(20))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detalles.componentes").exists());
    }

    @Test
    @DisplayName("El perfil no incluye el hash de contrasena")
    void elPerfilNoExponeElHash() throws Exception {
        usuarioExistente(1L);

        String cuerpo = mockMvc.perform(get("/api/v1/usuarios/1").with(como(1L)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Incluir el hash en el DTO de respuesta convertiria cualquier
        // forgot-password en una fuga.
        Assertions.assertThat(cuerpo)
                .doesNotContain("$2a$")
                .doesNotContain("hash")
                .contains("ana@ejemplo.com");
    }

    @Test
    @DisplayName("Una cuenta inexistente y una desactivada dan el mismo 404")
    void inexistenteYDesactivadaSonIndistinguibles() throws Exception {
        // Tres casos que el cliente NO puede distinguir, y no por
        // casualidad: los tres significan "no tienes nada aqui". El
        // cuarto, "es de otro usuario", tambien.
        Mockito.when(usuarios.buscarPorId(999L)).thenReturn(null);

        Usuario desactivado = new Usuario("ana@ejemplo.com", "Ana", "hash");
        desactivado.desactivar();
        Mockito.when(usuarios.buscarPorId(1L)).thenReturn(desactivado);

        mockMvc.perform(get("/api/v1/usuarios/999").with(como(999L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("NO_ENCONTRADO"));

        mockMvc.perform(get("/api/v1/usuarios/1").with(como(1L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("NO_ENCONTRADO"));

        mockMvc.perform(get("/api/v1/usuarios/2").with(como(1L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("NO_ENCONTRADO"));
    }

    @Test
    @DisplayName("El tamano de pagina se acota a 100")
    void paginaSeAcota() throws Exception {
        usuarioExistente(1L);
        Mockito.when(transacciones.historial(
                        ArgumentMatchers.anyLong(),
                        ArgumentMatchers.anyBoolean(),
                        ArgumentMatchers.any()))
                .thenAnswer(inv -> {
                    Pageable page = inv.getArgument(2);
                    Assertions.assertThat(page.getPageSize())
                            .as("El limite de 100 evita que un cliente "
                                + "pida la tabla entera")
                            .isLessThanOrEqualTo(100);
                    return List.of();
                });

        mockMvc.perform(get("/api/v1/usuarios/1/transacciones?tamano=99999")
                        .with(como(1L)))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------
    // Propiedad: la parte que faltaba
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Una cuenta desactivada pierde el acceso aunque su token sea valido")
    void unaCuentaDesactivadaPierdeElAcceso() throws Exception {

        // Un JWT no se puede revocar: la firma vale hasta que caduca, y el
        // token vive una hora. Medido contra el stack antes de comprobar el
        // estado de la cuenta:
        //
        //   token emitido con la cuenta ACTIVA
        //   UPDATE usuario SET activo = false
        //   GET  perfil        -> 200
        //   GET  historial     -> 200
        //   POST transaccion   -> 200   (ademas escribe en la BD)
        //
        // Desactivar una cuenta, que es la medida que se toma al sospechar
        // de un robo, no cortaba nada durante esa hora.
        Usuario desactivado = new Usuario("ana@ejemplo.com", "Ana", "hash");
        desactivado.desactivar();
        Mockito.when(usuarios.buscarPorId(1L)).thenReturn(desactivado);

        mockMvc.perform(get("/api/v1/usuarios/1").with(como(1L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("NO_ENCONTRADO"));

        // Y el mismo 404 mudo que si fuera de otro usuario: un 403 aqui
        // confirmaria que el id existe.
        mockMvc.perform(post("/api/v1/usuarios/1/transacciones")
                        .with(como(1L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(TRANSACCION_VALIDA.formatted(componentes(28))))
                .andExpect(status().isNotFound());

        Mockito.verify(transacciones, Mockito.never())
                .analizarYGuardar(ArgumentMatchers.anyLong(),
                                  ArgumentMatchers.any());
    }

    @Test
    @DisplayName("El historial de OTRO usuario devuelve 404, y no lista nada")
    void elHistorialAjenoDa404() throws Exception {

        // Este es el fallo que se demostro en ejecucion antes del cambio:
        // con la API key por cabecera, /usuarios/1/transacciones y
        // /usuarios/2/transacciones devolvian ambos 200 con la misma
        // credencial, porque la clave identifica a la aplicacion y no a
        // la persona.
        //
        // Ahora el token va en el subject, asi que se puede nyata en un
        // test de slice: el token es de Ana y se pide el historial de
        // Bruno.
        usuarioExistente(2L);

        mockMvc.perform(get("/api/v1/usuarios/2/transacciones").with(como(1L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("NO_ENCONTRADO"));

        // Y lo mas importante: ni se consulta. Un 404 que abre la base de
        // datos para comprobar y luego negar sigue filtrando informacion
        // por el tiempo de respuesta.
        Mockito.verify(transacciones, Mockito.never())
                .historial(ArgumentMatchers.anyLong(),
                           ArgumentMatchers.anyBoolean(),
                           ArgumentMatchers.any());
    }

    @Test
    @DisplayName("404 y no 403: un 403 confirmaria que el usuario existe")
    void noSeConfirmaLaExistencia() throws Exception {

        usuarioExistente(2L);

        int codigo = mockMvc.perform(get("/api/v1/usuarios/2").with(como(1L)))
                .andReturn().getResponse().getStatus();

        // Un 403 responderia "existe, pero no es tuyo", lo que permite
        // enumerar usuarios validos probando identificadores. El 404 no
        // distingue "no existe" de "no es tuyo", que es justo lo que se
        // quiere.
        Assertions.assertThat(codigo)
                .as("403 delataria que el usuario existe")
                .isEqualTo(404);
    }

    @Test
    @DisplayName("Analizar sobre el historial de otro tampoco: 404 sin inferencia")
    void analizarAjenoNoGastaInferencia() throws Exception {

        // Antes de comprobar que el usuario existe hay que comprobar de
        // quien es. Si se invirtiera el orden, un token ajeno podria
        // gastar inferencias y huecos del modelo de riesgo de otro.
        mockMvc.perform(post("/api/v1/usuarios/2/transacciones")
                        .with(como(1L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(TRANSACCION_VALIDA.formatted(componentes(28))))
                .andExpect(status().isNotFound());

        Mockito.verify(transacciones, Mockito.never())
                .analizarYGuardar(ArgumentMatchers.anyLong(),
                                  ArgumentMatchers.any());
    }

    @Test
    @DisplayName("Sin token no se ve ni el historial propio")
    void sinTokenNoHayHistorial() throws Exception {

        usuarioExistente(1L);

        mockMvc.perform(get("/api/v1/usuarios/1/transacciones"))
                .andExpect(status().isUnauthorized());

        Mockito.verify(transacciones, Mockito.never())
                .historial(ArgumentMatchers.anyLong(),
                           ArgumentMatchers.anyBoolean(),
                           ArgumentMatchers.any());
    }

    @Test
    @DisplayName("El historial NO tiene variant global sin propietario")
    void noExisteListadoGlobal() throws Exception {

        // Si existiera GET /api/v1/transacciones sin {id}, cualquier
        // cliente podria listar las transacciones de todos. Este test
        // falla si alguien lo anade.
        //
        // Va con token para que el 404 sea el de "no existe la ruta" y no
        // un 401 de seguridad, que seria un test que pasa por el motivo
        // equivocado.
        mockMvc.perform(get("/api/v1/transacciones").with(como(1L)))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/transacciones?usuarioId=1").with(como(1L)))
                .andExpect(status().isNotFound());
    }
}
