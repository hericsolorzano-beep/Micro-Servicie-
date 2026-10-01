package com.ejemplo.microservicio.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ejemplo.microservicio.config.ConfiguracionJwt;
import com.ejemplo.microservicio.controller.AnalisisController;
import com.ejemplo.microservicio.dto.RespuestaFraude;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import com.nimbusds.jose.jwk.source.ImmutableSecret;

/**
 * Tests de la autenticacion por token de sesion.
 *
 * Aqui se ejerce el filtro de verdad: los tokens se firman con la misma
 * libreria que usa el servicio y no se falsea ningun validador. Un filtro
 * que deja pasar lo que deberia rechazar es el fallo tipico de seguridad,
 * y solo aparece al ejecutarlo.
 *
 * Sustituyen a SeguridadApiKeyTest, que probaba el esquema anterior. Ese
 * esquema era insuficiente, y el fallo se demonstro en ejecucion: con una
 * sola clave compartida, /usuarios/1/transacciones y
 * /usuarios/2/transacciones devolvian ambos 200, porque la clave
 * identifica a la aplicacion y no a la persona.
 */
@WebMvcTest(controllers = AnalisisController.class)
@Import({ConfiguracionSeguridad.class, ConfiguracionJwt.class,
        EmisorDeToken.class})
@TestPropertySource(properties =
        "jwt.secret=clave-de-prueba-larga-suficiente-1234567890")
class SeguridadJwtTest {

    private static final String SECRETO = "clave-de-prueba-larga-suficiente-1234567890";

    private static final String CUERPO = """
            {"componentes":[1.234,1.234,1.234,1.234,1.234,1.234,1.234,1.234,1.234,1.234,1.234,1.234,1.234,1.234,1.234,1.234,1.234,1.234,1.234,1.234,1.234,1.234,1.234,1.234,1.234,1.234,1.234,1.234],"monto":100,"hora":12,"pais":"ES","distancia_km":50}}""";;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EmisorDeToken emisor;

    @MockitoBean
    private com.ejemplo.microservicio.service.AnalisisService analisisService;

    @BeforeEach
    void preparar() throws Exception {
        org.mockito.Mockito.when(analisisService.analizarTransaccion(
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(new RespuestaFraude(
                        false, 0.01, "bajo", "rf", "APROBADA", 5L, 0.30,
                        "componentes_PCA"));
    }

    // ------------------------------------------------------------------
    // Lo que se rechaza
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Sin token devuelve 401 y el endpoint no se ejecuta")
    void sinTokenDa401() throws Exception {

        mockMvc.perform(post("/api/transacciones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CUERPO))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.codigo").value("NO_AUTENTICADO"));

        // Lo importante: el controlador NO debe llegar a ejecutarse. Un
        // 401 con el servicio llamado igualmente daria acceso sin token.
        org.mockito.Mockito.verifyNoInteractions(analisisService);
    }

    @Test
    @DisplayName("Token con basura devuelve 401")
    void tokenBasuraDa401() throws Exception {

        mockMvc.perform(post("/api/transacciones")
                        .header("Authorization", "Bearer no-es-un-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CUERPO))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("El 401 llega en JSON, no con el cuerpo vacio de Spring")
    void el401EsJson() throws Exception {

        String cuerpo = mockMvc.perform(get("/api/v1/usuarios/1"))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        // Spring devuelve por defecto un 401 sin cuerpo. Un cliente que
        // espera JSON recibe algo que no puede parsear y no lo distingue
        // de un corte de red.
        org.assertj.core.api.Assertions.assertThat(cuerpo)
                .contains("NO_AUTENTICADO")
                .contains("caducado");
    }

    @Test
    @DisplayName("Token firmado con OTRA clave devuelve 401")
    void tokenDeOtraClaveDa401() throws Exception {

        // Reutilizar el token de otro despliegue, o de otro servicio que
        // tenga la misma clave por descuido. La verificacion de firma es
        // la unica que lo para.
        String ajeno = firmarCon("una-clave-totalmente-distinta",
                Instant.now(), Instant.now().plusSeconds(600), ConfiguracionJwt.EMISOR);

        mockMvc.perform(post("/api/transacciones")
                        .header("Authorization", "Bearer " + ajeno)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CUERPO))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Token SIN FIRMAR (alg=none) devuelve 401")
    void tokenSinFirmarDa401() throws Exception {

        // El ataque mas simple y el que mas veces ha funcionado en APIs
        // reales: un token sin firma con cabecera {"alg":"none"} y cuerpo
        // {"sub":"1"}. Si el servicio confiase en el contenido sin
        // comprobar la firma, esto seria acceso total.
        //
        // Se construye a mano porque ninguna libreria de emision firma
        // asi: para producir este token hay que escribirlo entero.
        String sinFirmar = base64("{\"alg\":\"none\"}")
                + "."
                + base64("{\"sub\":\"1\",\"iss\":\"" + ConfiguracionJwt.EMISOR
                + "\",\"exp\":" + Instant.now().plusSeconds(600).getEpochSecond() + "}")
                + ".";

        mockMvc.perform(post("/api/transacciones")
                        .header("Authorization", "Bearer " + sinFirmar)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CUERPO))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Token caducado devuelve 401")
    void tokenCaducadoDa401() throws Exception {

        // Firmado bien, emitido hace dos horas. Si solo se mirase la
        // firma pasaria, y las sesiones no tendrian caducidad.
        Instant haceDosHoras = Instant.now().minusSeconds(7200);

        String caducado = firmarCon(SECRETO, haceDosHoras,
                haceDosHoras.plusSeconds(3600), ConfiguracionJwt.EMISOR);

        mockMvc.perform(post("/api/transacciones")
                        .header("Authorization", "Bearer " + caducado)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CUERPO))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Token de OTRO emisor devuelve 401")
    void tokenDeOtroEmisorDa401() throws Exception {

        // Firma valida y sin caducar, pero emitida por otro servicio. Sin
        // validar el emisor, cualquier servicio que comparta la clave
        // firmaria tokens con acceso a datos de transacciones.
        //
        // Este test documenta por que ConfiguracionJwt hace
        // setJwtValidator: NimbusJwtDecoder con constructor valida firma
        // y fechas, pero NO el emisor.
        String ajeno = firmarCon(SECRETO, Instant.now(),
                Instant.now().plusSeconds(600), "otro-servicio");

        mockMvc.perform(post("/api/transacciones")
                        .header("Authorization", "Bearer " + ajeno)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CUERPO))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------
    // Lo que se acepta
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Con token valido la peticion pasa")
    void tokenValidoPasa() throws Exception {

        mockMvc.perform(post("/api/transacciones")
                        .header("Authorization", "Bearer " + emisor.emitir(1L, "ana@ejemplo.com"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CUERPO))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("El token lleva el id en el subject y el emisor correcto")
    void elTokenLlevaLaIdentidad() throws Exception {

        // El subject es lo que ComprobadorDePropiedad compara con el id
        // de la ruta. Si dejara de ser el id, toda la autorizacion
        // silenciosamente compararia otra cosa.
        String payload = new String(
                Base64.getUrlDecoder().decode(
                        emisor.emitir(42L, "ana@ejemplo.com").split("\\.")[1]),
                StandardCharsets.UTF_8);

        org.assertj.core.api.Assertions.assertThat(payload)
                .contains("\"sub\":\"42\"")
                .contains("\"iss\":\"" + ConfiguracionJwt.EMISOR + "\"");
    }

    // ------------------------------------------------------------------
    // Rutas publicas
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Una ruta de la lista publica no la corta la seguridad")
    void unaRutaPublicaNoDa401() throws Exception {

        // El login esta en la lista de publicas, asi que la seguridad no
        // debe cortarlo. Aqui sale 404 y no 401 porque este slice solo
        // carga AnalisisController y SesionController no existe; lo que se
        // comprueba es exactamente eso: que la peticion llega al
        // dispatcher en vez de rebotar en el filtro.
        //
        // Que el login responda de verdad se comprueba en
        // IntegracionCompletaTest, con el contexto entero.
        mockMvc.perform(get("/api/v1/sesiones/login"))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    /**
     * Firma un token con la MISMA libreria y algoritmo del servicio, pero
     * con el contenido que cada test necesita manipolar.
     */
    private String firmarCon(String secreto, Instant emitido, Instant caduca,
                             String emisor) {

        var encoder = new NimbusJwtEncoder(new ImmutableSecret(
                new SecretKeySpec(sha256(secreto), "HmacSHA256")));

        return encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(),
                JwtClaimsSet.builder()
                        .subject("1")
                        .issuer(emisor)
                        .issuedAt(emitido)
                        .expiresAt(caduca)
                        .build())).getTokenValue();
    }

    private static byte[] sha256(String texto) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(texto.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String base64(String texto) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(texto.getBytes(StandardCharsets.UTF_8));
    }
}
