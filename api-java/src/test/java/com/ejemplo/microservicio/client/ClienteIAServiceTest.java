package com.ejemplo.microservicio.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.ejemplo.microservicio.dto.PeticionFraudePython;
import com.ejemplo.microservicio.dto.PeticionTextoPython;
import com.ejemplo.microservicio.exception.ServicioIANoDisponibleException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Tests del cliente HTTP contra un servidor simulado.
 *
 * Estos tests usan un MockRestServiceServer en vez de @Mockito.mock del
 * RestClient, y esa diferencia importa: aqui se serializa el record con
 * Jackson de verdad y se deserializa la respuesta de verdad. Si alguien
 * cambia el nombre de un campo del DTO, este test falla; con un mock, el
 * JSON nunca llega a existir y el error pasaria desapercibido hasta
 * produccion.
 */
class ClienteIAServiceTest {

    /** 28 componentes PCA validos, como los espera el modelo. */
    private static java.util.List<Double> componentes() {
        return java.util.stream.IntStream.range(0, 28)
                .mapToObj(i -> 1.234)
                .toList();
    }

    // bindTo espera el Builder, no el RestClient ya construido: es el
    // builder el que puede sustituir la capa de transporte por el mock.
    private final RestClient.Builder builder = RestClient.builder()
            .baseUrl("http://ia-python:8000");

    private final MockRestServiceServer servidor =
            MockRestServiceServer.bindTo(builder).build();

    private final ClienteIAService cliente = new ClienteIAService(builder.build());

    @Test
    @DisplayName("Serializa con snake_case, el nombre que espera Pydantic")
    void serializaEnSnakeCase() throws Exception {
        servidor.expect(requestTo("http://ia-python:8000/predict/fraude"))
                .andExpect(request -> {
                    String cuerpo = ((org.springframework.mock.http.client.MockClientHttpRequest)
                            request).getBodyAsString();
                    // distancia_km, NO distanciaKm: si esto cambia, Python
                    // recibe null y responde 422.
                    assertThat(cuerpo).contains("\"distancia_km\"");
                    assertThat(cuerpo).contains("\"componentes\"");
                    assertThat(cuerpo).doesNotContain("distanciaKm");
                    assertThat(cuerpo).contains("\"monto\"").contains("\"hora\"")
                            .contains("\"pais\"");
                })
                .andRespond(withSuccess(
                        "{\"es_fraude\":true,\"probabilidad\":0.93,"
                        + "\"nivel_riesgo\":\"critico\",\"modelo\":\"rf\"}",
                        MediaType.APPLICATION_JSON));

        assertThat(cliente.predecirFraude(
                new PeticionFraudePython(componentes(), 9000.0, 3, "NG", 5200.0)).esFraude())
                .isTrue();

        servidor.verify();
    }

    @Test
    @DisplayName("Deserializa la respuesta y tolera campos que no conhece")
    void deserializaRespuesta() throws Exception {
        // "nuevo_campo" simula que Python se despliega antes que Java.
        // Con @JsonIgnoreProperties no debe romper nada.
        servidor.expect(requestTo("http://ia-python:8000/predict/fraude"))
                .andRespond(withSuccess(
                        "{\"es_fraude\":false,\"probabilidad\":0.01,"
                        + "\"nivel_riesgo\":\"bajo\",\"modelo\":\"rf\","
                        + "\"nuevo_campo\":\"ignorado\"}",
                        MediaType.APPLICATION_JSON));

        var r = cliente.predecirFraude(new PeticionFraudePython(componentes(), 150.0, 12, "ES", 80.0));

        assertThat(r.esFraude()).isFalse();
        assertThat(r.nivelRiesgo()).isEqualTo("bajo");
    }

    @Test
    @DisplayName("Cuerpo vacio se reporta como dependencia caida, no como NPE")
    void cuerpoVacioEsCaidaDeIa() {
        servidor.expect(requestTo("http://ia-python:8000/predict/fraude"))
                .andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        // Sin esta comprobacion, el null llegaria a AnalisisService y
        // explotaria con un NullPointerException -> 500 opaco.
        assertThatThrownBy(() ->
                cliente.predecirFraude(new PeticionFraudePython(componentes(), 150.0, 12, "ES", 80.0)))
                .isInstanceOf(ServicioIANoDisponibleException.class)
                .hasMessageContaining("incompleta");
    }

    @Test
    @DisplayName("Campo renombrado en Python se reporta como respuesta incompleta")
    void campoRenombradoEsRespuestaIncompleta() {
        // Simula que Python renombra nivel_riesgo. Con
        // @JsonIgnoreProperties no hay excepcion: llega null, y por eso
        // el cliente lo convierte en excepcion de dominio.
        servidor.expect(requestTo("http://ia-python:8000/predict/fraude"))
                .andRespond(withSuccess(
                        "{\"es_fraude\":true,\"probabilidad\":0.9}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() ->
                cliente.predecirFraude(new PeticionFraudePython(componentes(), 9000.0, 3, "NG", 5200.0)))
                .isInstanceOf(ServicioIANoDisponibleException.class)
                .hasMessageContaining("incompleta");
    }

    @Test
    @DisplayName("El texto viaja en el campo 'texto'")
    void textoEnCampoTexto() throws Exception {
        servidor.expect(requestTo("http://ia-python:8000/predict/sentimiento"))
                .andExpect(request -> {
                    String cuerpo = ((org.springframework.mock.http.client.MockClientHttpRequest)
                            request).getBodyAsString();
                    assertThat(cuerpo).contains("\"texto\"");
                })
                .andRespond(withSuccess(
                        "{\"sentimiento\":\"negativo\",\"confianza\":0.96,"
                        + "\"probabilidades\":{\"negativo\":0.96},\"modelo\":\"tfidf\"}",
                        MediaType.APPLICATION_JSON));

        var r = cliente.predecirSentimiento(new PeticionTextoPython("pésima calidad"));

        assertThat(r.sentimiento()).isEqualTo("negativo");
    }
}