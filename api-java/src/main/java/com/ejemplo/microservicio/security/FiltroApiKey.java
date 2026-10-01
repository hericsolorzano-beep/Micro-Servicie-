package com.ejemplo.microservicio.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Filtro que exige la cabecera X-API-Key.
 *
 * El mensaje de error es deliberadamente corto y NO dice por que fallo.
 * Un "falta la cabecera" o un "clave incorrecta" son dos Respuestas
 * distintas que confirmarian al atacante que su intento va por buen
 * camino, y permitirian automatizar la busqueda. La version anterior de
 * este mensaje decia "Credenciales ausentes o invalidas", que es una de
 * esas: el test de abajo la rechazo.
 *
 * Por que un filtro y no la seguridad de Spring: para autenticar con una
 * clave de API simple no hace falta todo el modelo de Spring Security
 * (usuarios, roles, sesiones). Un filtro hace el trabajo en 40 lineas, sin
 * configuracion de beans ni la pagina de login que genera Spring por
 * defecto cuando no hay nada configurado.
 *
 * La respuesta 401 NO lleva detalle de por que fallo: ni "clave
 * incorrecta" ni "falta la cabecera". Distinguir esos casos ayuda al
 * atacante a saber si va por buen camino.
 */
@Component
public class FiltroApiKey extends OncePerRequestFilter {

    private static final String CABECERA = "X-API-Key";

    private final ValidadorApiKey validador;

    public FiltroApiKey(ValidadorApiKey validador) {
        this.validador = validador;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {

        // Sin clave configurada, el filtro no bloquea nada. Es lo que
        // permite desarrollo sin autenticacion.
        if (!validador.estaActivada()) {
            chain.doFilter(request, response);
            return;
        }

        // Seguridad activada pero SIN clave configurada: esto es un
        // error de despliegue, no una peticion invalida. Sin esta
        // comprobacion, MessageDigest.isEqual("", "") devolveria true y
        // la API aceptaria una clave VACIA: abierto por un descuido de
        // configuracion, que es la peor forma de estar abierto.
        if (validador.isValida() && request.getHeader(CABECERA) != null
                && request.getHeader(CABECERA).isEmpty()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(
                    "{\"codigo\":\"NO_AUTENTICADO\",\"mensaje\":\"No autorizado\",\"detalles\":{}}");
            return;
        }

        if (!validador.esValida(request.getHeader(CABECERA))) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(
                    "{\"codigo\":\"NO_AUTENTICADO\","
                    + "\"mensaje\":\"No autorizado\","
                    + "\"detalles\":{}}");
            return;
        }

        chain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!validador.estaActivada()) {
            return true;
        }
        String ruta = request.getRequestURI();
        for (String publica : new String[]{"/actuator/health"}) {
            if (ruta.equals(publica) || ruta.startsWith(publica + "/")) {
                return true;
            }
        }
        return false;
    }
}