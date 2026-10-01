package com.ejemplo.microservicio.security;

import com.ejemplo.microservicio.exception.AccesoDenegadoException;
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

    /**
     * @param recursoId id del recurso solicitado en la ruta
     * @throws AccesoDenegadoException si el token es de otro usuario
     */
    public void exigir(Long recursoId) {

        Long usuarioDelToken = usuarioActual();

        if (usuarioDelToken == null) {
            // No deberia llegar aqui: la cadena de seguridad ya exige un
            // token. Si llega, es un bug de configuracion y conviene que
            // se note con un 403, no con un null silencioso.
            throw new AccesoDenegadoException("No se pudo determinar el usuario");
        }

        if (!usuarioDelToken.equals(recursoId)) {
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