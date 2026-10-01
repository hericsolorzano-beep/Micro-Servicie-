package com.ejemplo.microservicio.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import com.nimbusds.jose.jwk.source.ImmutableSecret;

/**
 * Firma y verificacion de los tokens de sesion.
 *
 * La clave simetrica viene de JWT_SECRET. Si no se define, se genera una
 * al arrancar y se avisa por el log y por el health check.
 *
 * El motivo de avisar en vez de fallar es que un despliegue sin secreto
 * NO esta roto de forma visible: la API responde 200 a todo el mundo y lo
 * unico que falla son las sesiones al reiniciar. Un arranque fallido seria
 * mas honesto, pero dejaria el desarrollo local sin poder levantarse sin
 * configurar nada; por eso el estado se publica en
 * /actuator/health (EstadoClaveJwt) en vez de fallarse en silencio.
 */
@Configuration
public class ConfiguracionJwt {

    private static final Logger log = LoggerFactory.getLogger(ConfiguracionJwt.class);

    /**
     * Identificador del servicio emisor.
     *
     * No es decorativo: ver el comentario de jwtDecoder().
     */
    public static final String EMISOR = "microservicio-api";

    /**
     * HS256 necesita al menos 256 bits (32 bytes) de clave. Una clave mas
     * corta se acepta pero es debil, asi que se estira con SHA-256 en vez
     * de rechazar el arranque: es mejor una clave derivada de 32 bytes
     * que un fallosorpresivo por un secreto de 16 caracteres.
     */
    private SecretKey construirClave(String secreto) {
        byte[] bytes;
        try {
            bytes = MessageDigest.getInstance("SHA-256")
                    .digest(secreto.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 esta en todos los JDK. Si faltara, el entorno esta
            // roto de una forma que no vale la pena diagnosticar aqui.
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Bean
    public SecretKey claveJwt(@Value("${jwt.secret:}") String secreto) {

        if (secreto == null || secreto.isBlank()) {
            log.warn("""
                    [JWT] No se definiio JWT_SECRET: se genera una clave \
                    aleatoria para esta ejecucion. Los tokens emitidos antes \
                    de reiniciar dejaran de valer y las sesiones abiertas se \
                    cerraran solas. Define JWT_SECRET para produccion.""");
            return construirClave(java.util.UUID.randomUUID().toString());
        }

        return construirClave(secreto);
    }

    /**
     * Si la clave vino de la configuracion o se genero sola.
     *
     * Lo consume el health check. No es un dato de negocio: es la forma
     * de que un despliegue mal configurado se note sin leer el log.
     */
    @Bean
    public EstadoClaveJwt estadoClaveJwt(@Value("${jwt.secret:}") String secreto) {
        boolean definida = secreto != null && !secreto.isBlank();
        return definida
                ? EstadoClaveJwt.deConfiguracion()
                : EstadoClaveJwt.generada();
    }

    @Bean
    public JwtEncoder jwtEncoder(SecretKey clave) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(clave));
    }

    /**
     * Verifica firma, emisor y caducidad. Los tres.
     *
     * Firmar solo no basta, y este bean es la prueba de por que:
     * NimbusJwtDecoder con constructor valida la firma y las marcas de
     * tiempo, pero NO el emisor. Sin setJwtValidator, un token con
     * firma valida emitido por cualquier otro servicio que comparta la
     * clave seria aceptado aqui, y por tanto dariera acceso a datos de
     * transacciones.
     */
    @Bean
    public JwtDecoder jwtDecoder(SecretKey clave) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(clave)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(EMISOR));
        return decoder;
    }
}
