package com.ejemplo.microservicio.config;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Configuracion del cliente HTTP hacia el servicio de Python.
 *
 * Los timeouts no son opcionales en un cliente entre microservicios. Sin
 * ellos, si Python se cuelga en una inferencia, el hilo de Tomcat que
 * atiende la peticion se queda bloqueado esperando. Con miles de
 * peticiones pendientes, agotas el pool de hilos y la API publica se cae
 * entera. Es el fallo en cascada clasico: una dependencia lenta tumba al
 * servicio que la consume.
 *
 * Detalle que costó una sesion de depuracion: HttpClient del JDK negocia
 * HTTP/2 en claro (h2c) por defecto. Contra Uvicorn eso produce cabeceras
 * Upgrade/HTTP2-Settings, el servidor responde 422 con cuerpo vacio y
 * parece que el body nunca se envia. Fijar HTTP_1_1 de forma explicita es
 * lo correcto para un microservicio: HTTP/2 sin cifrar solo tiene sentido
 * detras de un proxy que lo termine.
 *
 * Aqui se construye el factory a mano en vez de usar
 * ClientHttpRequestFactoryBuilder.detect() porque necesitamos pasar la
 * version del protocolo, y ese builder no la expone.
 */
@Configuration
public class ConfiguracionClienteIA {

    @Bean
    public RestClient clienteIA(
            RestClient.Builder builder,
            @Value("${ia.base-url}") String baseUrl,
            @Value("${ia.connect-timeout-seconds:3}") long connectTimeout,
            @Value("${ia.read-timeout-seconds:10}") long readTimeout) {

        var httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(connectTimeout))
                .build();

        // El read timeout lo aplica la request factory, no el HttpClient:
        // es el tiempo maximo que se espera los bytes de la respuesta.
        var factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(readTimeout));

        return builder
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }
}