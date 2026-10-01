package com.ejemplo.microservicio;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * API publica. Unico punto de entrada de los clientes externos.
 *
 * No sabe nada de modelos de IA: solo sabe llamar a otro servicio HTTP.
 * Esa ignorancia es deliberada y es lo que permite cambiar el motor de
 * inferencia sin tocar el codigo de negocio.
 */
@SpringBootApplication
public class MicroservicioApplication {

    public static void main(String[] args) {
        SpringApplication.run(MicroservicioApplication.class, args);
    }
}