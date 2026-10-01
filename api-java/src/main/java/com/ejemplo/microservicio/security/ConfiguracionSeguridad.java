package com.ejemplo.microservicio.security;

import com.ejemplo.microservicio.controller.SesionController;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * Cadena de seguridad basada en token de sesion por usuario.
 *
 * Publicas: el alta y el login, y el health. Todo lo demas exige un token
 * valido. Y no basta con que sea valido: el id del token tiene que
 * coincidir con el recurso solicitado, y eso lo comprueba
 * ComprobadorDePropiedad, en el servicio.
 *
 * Esa segunda parte es la que faltaba antes. Con la API key por cabecera
 * todos los clientes compartian credencial, asi que cualquiera que la
 * tuviera podia leer el historial de cualquier usuario; se comprobo en
 * ejecucion, con /usuarios/1/transacciones y /usuarios/2/transacciones
 * devolviendo ambos 200 con la misma clave.
 */
@Configuration
public class ConfiguracionSeguridad {

    @Bean
    public SecurityFilterChain cadena(HttpSecurity http) throws Exception {

        // Con tokens no hay sesion ni cookies, asi que CSRF no aplica: un
        // cliente que se autentica con una cabecera Authorization no puede
        // ser victima de CSRF, porque el navegador no anade esa cabecera
        // por su cuenta. Desactivarlo aqui no es un agujero: es la
        // consecuencia de no usar cookies.
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(s ->
                        s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**")
                        .permitAll()
                        // Las publicas se nombran una a una, no con un
                        // comodin sobre /sesiones/**. Con el comodin, un
                        // endpoint futuro bajo ese prefijo (por ejemplo
                        // /sesiones/{id}/revocar) quedaria publico sin que
                        // nadie se entere al anadirlo, y no habria ningun
                        // test que lo detectara.
                        .requestMatchers(SesionController.RUTA_LOGIN).permitAll()
                        .requestMatchers(SesionController.RUTA_ALTA).permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> { })
                        .authenticationEntryPoint(entradaJson())
                        .accessDeniedHandler(denegadoJson()));

        return http.build();
    }

    /**
     * 401 en JSON, no con cuerpo vacio.
     *
     * Spring devuelve por defecto un 401 sin cuerpo. Un cliente que
     * espera JSON recibe algo que no puede parsear y no distingue el
     * fallo de autenticacion de un corte de red. Ademas sin cuerpo no
     * hay forma de saber si el token falto, vencio o tenia la firma
     * mal, que son tres causas de trabajo muy distintas.
     */
    private org.springframework.security.web.AuthenticationEntryPoint entradaJson() {
        return (request, response, ex) -> {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(
                    "{\"codigo\":\"NO_AUTENTICADO\","
                    + "\"mensaje\":\"Falta un token de sesion valido o ha caducado\","
                    + "\"detalles\":{}}");
        };
    }

    /**
     * 403 en JSON.
     *
     * Cuando aparece un 403 es que hay token valido pero sin permiso
     * sobre el recurso. El caso normal (pedir el historial de otro) no
     * llega aqui: ComprobadorDePropiedad lo convierte en 404 para no
     * confirmar que el usuario existe.
     */
    private AccessDeniedHandler denegadoJson() {
        return (request, response, ex) -> {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(
                    "{\"codigo\":\"ACCESO_DENEGADO\","
                    + "\"mensaje\":\"No tienes permiso para este recurso\","
                    + "\"detalles\":{}}");
        };
    }
}
