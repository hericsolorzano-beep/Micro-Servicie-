package com.ejemplo.microservicio.security;

import com.ejemplo.microservicio.domain.Usuario;
import com.ejemplo.microservicio.exception.AccesoDenegadoException;
import com.ejemplo.microservicio.service.UsuarioService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Comprueba que el usuario del token es el dueño del recurso.
 *
 * ESTA es la pieza que faltaba. La API key anterior autenticaba a la
 * APLICACION: todos los clientes compartian credencial, asi que
 * cualquiera que la tuviera podia leer el historial de cualquier usuario.
 * Verificado en ejecucion: con la misma clave, /usuarios/1/transacciones
 * y /usuarios/2/transacciones devolvian ambos 200.
 *
 * Ahora el token lleva el id del usuario en el subject, y esta clase
 * comprueba que coincide con el recurso solicitado.
 *
 * El fallo es 404 y no 403 a proposito: un 403 confirma que el recurso
 * EXISTE pero no es tuyo, y permitiria enumerar usuarios validos
 * probando identificadores. El 404 no revela nada. El coste es que un
 * cliente que pide el recurso equivocado no entiende el motivo, que es
 * el intercambio habitual en este tipo de API.
 */
@Component
public class ComprobadorDePropiedad {

    private static final Logger log =
            LoggerFactory.getLogger(ComprobadorDePropiedad.class);

    /**
     * Exige que el token sea del dueño del recurso Y que la cuenta siga
     * activa.
     *
     * La segunda mitad es la que faltaba, y se encontró midiendo. Un JWT
     * no se puede revocar: la firma es válida hasta que caduca, y el
     * token vive una hora. Medido contra el stack:
     *
     *   token emitido con la cuenta ACTIVA
     *   UPDATE usuario SET activo = false
     *   GET  /usuarios/9                 -> 200
     *   GET  /usuarios/9/transacciones   -> 200
     *   POST /usuarios/9/transacciones   -> 200   (ademas escribe en la BD)
     *
     * Es decir: desactivar una cuenta, que es justo la medida que se toma
     * cuando se sospecha de un robo, no cortaba nada. El campo `activo`
     * existia y solo se usaba en el login; con un token ya emitido, la
     * cuenta desactivada podia seguir operando una hora.
     *
     * La comprobacion va aqui y no en el emisor porque la revocacion solo
     * puede vivir del lado del servidor: el token ya esta en manos del
     * cliente y no se puede llamar de vuelta.
     *
     * @param recursoId id del recurso solicitado en la ruta
     * @param usuarios  para leer el estado real de la cuenta
     * @throws AccesoDenegadoException si el token es de otro usuario, o si
     *         la cuenta ya no esta activa
     */
    public void exigir(Long recursoId, UsuarioService usuarios) {

        Long usuarioDelToken = usuarioActual();

        if (usuarioDelToken == null) {
            // No deberia llegar aqui: la cadena de seguridad ya exige un
            // token, asi que si llega es que el subject no era un id
            // numerico.
            //
            // Se responde con el MISMO 404 que el caso de "es de otro",
            // no con un mensaje propio. Un texto distinto seria un bit
            // extra: confirmaria que el token es valido pero raro, y en un
            // despliegue donde el secreto se comparte con otro servicio,
            // eso distingue un token bien formado de uno manipulado. El
            // motivo real va al log, que es donde se diagnostica.
            log.warn("Token sin subject numerico; se responde 404. "
                    + "Revisar si otro servicio comparte JWT_SECRET.");
            throw AccesoDenegadoException.comoSiNoExistiera();
        }

        if (!usuarioDelToken.equals(recursoId)) {
            throw AccesoDenegadoException.comoSiNoExistiera();
        }

        // Mismo 404 mudo que "es de otro usuario". Un 403 aqui confirmaria
        // que el id existe, que es justo lo que se quiere evitar.
        Usuario usuario = usuarios.buscarPorId(usuarioDelToken);

        if (usuario == null || !usuario.isActivo()) {
            log.info("Peticion rechazada para el usuario {}: cuenta inactiva "
                    + "o inexistente. El token sigue siendo valido hasta que "
                    + "caduca.", usuarioDelToken);
            throw AccesoDenegadoException.comoSiNoExistiera();
        }
    }

    /**
     * El id del usuario del token, o null si no hay ninguno.
     *
     * Se lee del claim "sub", que es donde EmisorDeToken lo escribe.
     */
    public Long usuarioActual() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
            return null;
        }

        String subject = jwt.getSubject();
        if (subject == null || subject.isBlank()) {
            return null;
        }

        try {
            return Long.valueOf(subject);
        } catch (NumberFormatException e) {
            // El subject deberia ser siempre un id numerico. Si no lo es,
            // el token fue emitido por otra cosa y no debe concederse
            // acceso a nada.
            return null;
        }
    }
}