package com.ejemplo.microservicio.security;

import java.time.Instant;
import com.ejemplo.microservicio.config.ConfiguracionJwt;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

/**
 * Emite tokens de sesion por usuario.
 *
 * POR QUE JWT Y NO LA API KEY QUE HABIA ANTES
 *
 * La API key identifica a la APLICACION que llama, no a la persona. Con
 * ella todos los clientes comparten credencial, asi que cualquiera que
 * la tenga puede leer el historial de cualquier usuario. Eso se comprobó
 * en ejecucion: con la misma clave, /usuarios/1/transacciones y
 * /usuarios/2/transacciones devolvian ambos 200.
 *
 * Aqui el token lleva el id del usuario en el subject, y cada peticion
 * se contrasta con el recurso que toca. Esa es la diferencia entre
 * autenticar a un cliente y autorizar a una persona.
 *
 * HS256 con clave simetrica: la misma clave firma y verifica. Sirve
 * mientras el servicio que emite sea el unico que verifica, que es el
 * caso. Si algun dia varios servicios verifican tokens emitidos aqui,
 * el algoritmo pasa a RS256 con clave publica.
 */
@Component
public class EmisorDeToken {

    private final JwtEncoder encoder;

    public EmisorDeToken(JwtEncoder encoder) {
        this.encoder = encoder;
    }

    /**
     * Emite un token para un usuario.
     *
     * El subject es el id, no el email: el email puede cambiar y el id no.
     *
     * El token lleva SOLO el id. No lleva el email, aunque el metodo lo
     * recibiera: un JWT va firmado pero NO cifrado, asi que cualquier claim
     * es legible por quien lo lea, y eso incluye un proxy, un log de
     * cabeceras y el propio cliente. Con el email dentro, el correo de
     * cada persona queda en manos de quien inspeccione el token, sin
     * necesidad de saber quien es. El parametro se conserva para no tocar
     * la firma en las llamadas, pero no se usa.
     */
    public String emitir(Long usuarioId, String email) {

        Instant ahora = Instant.now();

        var claims = JwtClaimsSet.builder()
                .subject(usuarioId.toString())
                .issuer(ConfiguracionJwt.EMISOR)
                .issuedAt(ahora)
                .expiresAt(ahora.plusSeconds(3600))
                .build();

        var cabeceras = JwsHeader.with(MacAlgorithm.HS256).build();

        return encoder.encode(
                JwtEncoderParameters.from(cabeceras, claims)).getTokenValue();
    }
}