package com.ejemplo.microservicio.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Respuesta crudo del servicio de IA, tal cual llega.
 *
 * @JsonIgnoreProperties(ignoreUnknown = true) es la red de seguridad: si
 * Python anade un campo, Spring lo ignora en vez de lanzar
 * UnrecognizedPropertyException. Un despliegue de Python no debe ser una
 * caida de produccion en Java.
 *
 * OJO: para campos QUE DESAPARECEN esto no ayuda. Jackson no lanza, llena
 * el campo con null. Por eso ClienteIAService comprueba que los campos
 * clave no vengan null antes de devolver nada.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RespuestaFraudePython(

        @JsonProperty("es_fraude")
        Boolean esFraude,

        @JsonProperty("probabilidad")
        Double probabilidad,

        @JsonProperty("nivel_riesgo")
        String nivelRiesgo,

        @JsonProperty("modelo")
        String modelo,

        /**
         * Umbral con el que el servicio decidio es_fraude.
         *
         * Viaja de vuelta a proposito: asi el cliente puede saber si el
         * veredicto venia de un umbral conservador (muchos falsos
         * positivos, pocos fraudulentos escapados) o agresivo, sin tener
         * que conocer la configuracion interna.
         */
        @JsonProperty("umbral")
        Double umbral
) {
}