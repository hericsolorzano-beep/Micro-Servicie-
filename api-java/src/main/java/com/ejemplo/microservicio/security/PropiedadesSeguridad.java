package com.ejemplo.microservicio.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Propiedades de seguridad, todas con valor por defecto sensato.
 *
 * El default de seguridad.activada es FALSE a proposito: en desarrollo
 * quieres poder hacer curl sin autenticacion. En produccion, Spring Boot
 * lee las variables de entorno y no se toca codigo ni configuracion.
 *
 * Ese es el riesgo real de este diseno: si el despliegue no define la
 * variable, la API queda abierta. Por eso el healthcheck reporta el
 * estado, para que se pueda comprobar sin mirar el codigo.
 *
 * @EnableConfigurationProperties (en AplicacionConfiguracion) es
 * OBLIGATORIO para records: un record con constructor no tiene metodo
 * set, asi que Spring no puede enlazarlo por reflexion como hace con las
 * clases javabean. Sin esa anotacion, el bean se crea pero sin valores y
 * falla al inyectar dependencias.
 *
 * Por eso NO lleva @Component: @EnableConfigurationProperties ya registra
 * el bean. Con las dos anotaciones Spring encuentra dos beans del mismo
 * tipo y falla al inyectar con "expected single matching bean but found 2".
 */
@ConfigurationProperties(prefix = "seguridad")
public record PropiedadesSeguridad(

        boolean activada,

        String clave,

        /** Endpoints que quedan abiertos aunque la seguridad este activa. */
        String[] rutasPublicas
) {

    public PropiedadesSeguridad {

        // Compact constructor: normaliza lo que llega de la configuracion.
        // Sin esto, `clave` puede ser null si la propiedad no esta definida
        // en el perfil activo, y revienta con un NPE DENTRO del constructor
        // de otro bean (ValidadorApiKey), con un error que no senala el
        // origen real.
        //
        // Un record no debe asumir que la configuracion esta completa: un
        // perfil de test, o un despliegue con variables mal definidas, no
        // la van a definir.
        clave = clave == null ? "" : clave;

        if (rutasPublicas == null) {
            rutasPublicas = new String[]{"/actuator/health", "/actuator/health/**"};
        }
    }
}