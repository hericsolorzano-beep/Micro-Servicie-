package com.ejemplo.microservicio.config;

import com.ejemplo.microservicio.security.PropiedadesSeguridad;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Habilita el enlace de PropiedadesSeguridad.
 *
 * Spring Boot 3 enlaza @ConfigurationProperties solo con el constructor si
 * la clase se registra con @EnableConfigurationProperties o con
 * @ConfigurationPropertiesScan. Un record no tiene setters, asi que el
 * mecanismo javabean clasico no funciona y el enlace silenciosamente no
 * ocurre: el bean se crea con los valores nulos y la aplicacion falla al
 * arrancar al inyectarlo.
 *
 * @ConfigurationPropertiesScan en la clase principal habria sido mas
 * limpio, pero obliga a que los tests de slice.Importen la clase a mano.
 * Este @Enable explicito es mas explicito y funciona en los tests.
 */
@Configuration
@EnableConfigurationProperties(PropiedadesSeguridad.class)
public class AplicacionConfiguracion {
}