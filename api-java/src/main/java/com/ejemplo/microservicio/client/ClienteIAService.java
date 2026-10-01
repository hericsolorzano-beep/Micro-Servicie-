package com.ejemplo.microservicio.client;

import com.ejemplo.microservicio.dto.PeticionFraudePython;
import com.ejemplo.microservicio.dto.PeticionTextoPython;
import com.ejemplo.microservicio.dto.RespuestaFraudePython;
import com.ejemplo.microservicio.dto.RespuestaSentimientoPython;
import com.ejemplo.microservicio.exception.ServicioIANoDisponibleException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Cliente HTTP del servicio de IA.
 *
 * Aislar las llamadas en una sola clase tiene dos beneficios que se pagan
 * solos: los timeouts y la traduccion de excepciones quedan en un sitio, y
 * los tests pueden sustituir esta clase por un doble sin tocar los
 * servicios de negocio.
 *
 * Este componente NO tiene anotaciones de transaccion ni logica: solo
 * transporte y traduccion de errores.
 */
@Component
public class ClienteIAService {

    private final RestClient restClient;

    public ClienteIAService(RestClient clienteIA) {
        this.restClient = clienteIA;
    }

    public RespuestaFraudePython predecirFraude(PeticionFraudePython peticion)
            throws ServicioIANoDisponibleException {

        try {
            RespuestaFraudePython r = restClient.post()
                    .uri("/predict/fraude")
                    .body(peticion)
                    .retrieve()
                    .body(RespuestaFraudePython.class);

            // Una respuesta nula o incompleta se trata como caida de la
            // dependencia, no como resultado valido. Sin esta comprobacion,
            // un campo renombrado en Python produce null en un record con
            // @JsonIgnoreProperties y el NPE aflora mas arriba como un 500
            // que no explica nada.
            if (r == null || r.esFraude() == null || r.nivelRiesgo() == null) {
                throw new ServicioIANoDisponibleException(
                        "El servicio de IA devolvio una respuesta incompleta");
            }
            return r;

        } catch (RestClientException e) {
            // RestClientException cubre timeouts, errores de conexion y
            // tambien respuestas 4xx/5xx de Python. Distinguirlos exigiria
            // catches separados; aqui solo hace falta distinguir entre
            // "pude obtener respuesta" y "no pude", que es lo que el
            // servicio de negocio necesita para decidir el fallback.
            throw new ServicioIANoDisponibleException(
                    "El servicio de IA no respondio correctamente", e);
        }
    }

    public RespuestaSentimientoPython predecirSentimiento(PeticionTextoPython peticion)
            throws ServicioIANoDisponibleException {

        try {
            RespuestaSentimientoPython r = restClient.post()
                    .uri("/predict/sentimiento")
                    .body(peticion)
                    .retrieve()
                    .body(RespuestaSentimientoPython.class);

            // confianza y probabilidades se comprueban tambien: el record las
            // expone al cliente externo, y un null ahi llega como
            // "confianza": null, rompiendo el formato uniforme de la
            // respuesta.
            if (r == null || r.sentimiento() == null
                    || r.confianza() == null || r.probabilidades() == null) {
                throw new ServicioIANoDisponibleException(
                        "El servicio de IA devolvio una respuesta incompleta");
            }
            return r;

        } catch (RestClientException e) {
            throw new ServicioIANoDisponibleException(
                    "El servicio de IA no respondio correctamente", e);
        }
    }

    /**
     * Sonda de disponibilidad del servicio de IA.
     *
     * La usa el health indicator de actuator (ver SaludIAIndicator), no
     * el camino de inferencia: una peticion de negocio no debe consultar
     * /health antes de llamar a /predict, solo duplicaria el trabajo. El
     * modelo ya devuelve 503 por si mismo cuando no puede atender.
     */
    public boolean estaDisponible() {
        try {
            var respuesta = restClient.get()
                    .uri("/health")
                    .retrieve()
                    .body(SaludIA.class);
            return respuesta != null
                    && respuesta.modelosCargados() != null
                    && respuesta.modelosCargados();

        } catch (RestClientException e) {
            return false;
        }
    }

    /** Mapa local de la respuesta de /health. */
    private record SaludIA(
            @com.fasterxml.jackson.annotation.JsonProperty("estado") String estado,
            @com.fasterxml.jackson.annotation.JsonProperty("modelos_cargados")
            Boolean modelosCargados
    ) {
    }
}