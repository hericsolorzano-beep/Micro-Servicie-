package com.ejemplo.microservicio.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Cadena de seguridad de Spring.
 *
 * El unico proposito de esta clase es APAGAR el comportamiento por defecto
 * de Spring Security, no anadirle nada. Sin este bean, Spring Boot aplica
 * su configuracion automatica: proteccion CSRF, cabecera de sesion, y una
 * pagina de login HTML en todas las peticiones. Para una API sin
 * formularios ni cookies, todo eso es ruido.
 *
 * La autenticacion real la hace FiltroApiKey, que se registra como filtro
 * de servlet normal y por tanto se ejecuta ANTES de esta cadena.
 *
 * CSRF se desactiva porque esta API no usa cookies ni sesion: un cliente
 * que demuestra su identidad con una cabecera propia no puede ser victima
 * de CSRF, ya que el navegador no anade cabeceras custom por su cuenta.
 */
@Configuration
// @WebMvcTest carga esta clase pero NO un SecurityFilterChain propio del
// test, asi que se aplica el csrf().disable() global que declara Spring
// Boot. En los tests de slice, Spring Security exige el token CSRF en los
// POST aunque el csrf() este off en la configuracion de produccion.
@EnableWebSecurity
public class ConfiguracionSeguridad {

    @Bean
    public SecurityFilterChain cadena(HttpSecurity http) throws Exception {

        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s ->
                        s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Sin pagina de login: un 401 debe devolver JSON, no HTML,
                // o el cliente no podria parsear la respuesta.
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .headers(headers -> headers.frameOptions(frame -> frame.deny()));

        return http.build();
    }
}