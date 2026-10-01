package com.ejemplo.microservicio.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Contrato PUBLICO de la API para analizar una transaccion.
 *
 * CAMBIO IMPORTANTE respecto a la version anterior: la senal del modelo
 * viaja en "componentes", no en campos sueltos como "hora" y "distancia_km".
 *
 * Motivo: el modelo se entrena con el dataset real de fraude, cuyas
 * variables V1..V28 son componentes PCA anonimizados. No existe un
 * concepto de "pais" o "distancia" en ese dataset, asi que mantenerlos
 * como senales del modelo habria sido fiction.
 *
 * "pais" y "distancia_km" siguen en el contrato porque son datos de
 * negocio que el cliente tiene y que se pueden mostrar y auditar. No
 *spath influyen en la prediccion, y el response lo dice explicitamente.
 */
public record SolicitudTransaccion(

        @NotNull(message = "los componentes son obligatorios")
        @Size(min = 28, max = 28,
                message = "deben venir exactamente 28 componentes (V1..V28)")
        // Rango validado, y no solo "que no sea null".
        //
        // Medido en el dataset real: V1..V28 van de -113,7 a 120,6, con
        // el 99,8% entre -7,3 y 5,2. Sin acotar, un cliente podia mandar
        // 1e30 en cualquier componente y, en un ensemble de arboles, los
        // valores extremos caen en las hojas extremas: es decir, el cliente
        // eligia la hoja y con ella el veredicto. Eso es exactamente la
        // funcion del endpoint, en manos de quien deberia ser la contraparte.
        //
        // El limite es 5x el maximo absoluto observado, no el maximo. Un
        // limite exacto rechazaria filas reales del dataset; este deja
        // margen para datos nuevos sin admitir absurdos.
        List<@NotNull @DecimalMin(value = "-500.0",
                message = "un componente esta fuera de rango")
                @DecimalMax(value = "500.0",
                message = "un componente esta fuera de rango")
                Double> componentes,

        @NotNull(message = "el monto es obligatorio")
        @DecimalMin(value = "0.01", message = "el monto debe ser mayor que cero")
        // El dataset real llega a 25.691,16. El limite anterior era
        // 1.000.000, un valor 39 veces mayor que cualquiera que el modelo
        // haya visto: no es "un limite generoso", es un hueco por el que
        // la senal de entrada cae fuera de la distribucion aprendida.
        @DecimalMax(value = "100000.00", message = "el monto excede el limite permitido")
        Double monto,

        @NotNull(message = "la hora es obligatoria")
        @Min(value = 0, message = "la hora debe estar entre 0 y 23")
        @Max(value = 23, message = "la hora debe estar entre 0 y 23")
        Integer hora,

        @NotBlank(message = "el pais es obligatorio")
        @Pattern(regexp = "^[A-Z]{2}$",
                message = "el pais debe ser un codigo ISO de 2 letras en mayusculas")
        @Size(min = 2, max = 2, message = "el pais debe tener exactamente 2 caracteres")
        String pais,

        // Informativo: no entra en el modelo.
        //
        // @JsonProperty es OBLIGATORIO: la convencion de Java es
        // distanciaKm y el JSON trae distancia_km. Sin la anotacion,
        // Jackson no encuentra el campo, distanciaKm queda null, y el
        // fallo aparece como un 400 de validación que no señala la causa
        // real. Es el mismo fallo que se corrigio antes en este DTO.
        @JsonProperty("distancia_km")
        @NotNull(message = "la distancia es obligatoria")
        @DecimalMin(value = "0.0", message = "la distancia no puede ser negativa")
        @DecimalMax(value = "40000.0", message = "la distancia excede el rango admitido")
        Double distanciaKm
) {
}