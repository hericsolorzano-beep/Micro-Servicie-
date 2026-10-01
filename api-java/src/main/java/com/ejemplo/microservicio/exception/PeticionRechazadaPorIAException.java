package com.ejemplo.microservicio.exception;

/**
 * El servicio de IA rechazo la peticion por su contenido (4xx).
 *
 * Existe separada de ServicioIANoDisponibleException porque son cosas
 * opuestas, y confundirlas tiene un coste medido.
 *
 * Un 422 de Python significa "estos datos no me sirven" (un pais fuera de
 * la lista, un numero de componentes incorrecto). Es un error de quien
 * pregunta, no de quien responde. Traducirlo a "IA no disponible" hacia
 * dos cosas malas a la vez:
 *
 *  1. Decirle al cliente 503 cuando su peticion esta mal, que es un 400.
 *     El cliente reintenta cosas que jamas van a funcionar.
 *
 *  2. Contarlo como FALLO del circuito. Y aqui esta el agujero: unas
 *     diez peticiones con un pais no soportado abrían el circuito, y
 *     durante 30 segundos TODAS las peticiones de fraude devolvian
 *     CIRCUITO_ABIERTO, incluidas las de clientes legitimos con datos
 *     correctos. Medido contra el stack en ejecucion: tras 6 peticiones
 *     con paises "no soportados" bien formados, una peticion con pais=ES
 *     dejo de responderse.
 *
 * O sea: un campo de texto libre permitia dejar el detector de fraude
 * entero caido para todo el mundo. En un sistema de pagos, desactivar la
 * deteccion de fraude no es una molestia: es el fallo que mas caro sale.
 *
 * Por eso esta excepcion NO esta en retry-exceptions ni cuenta como
 * fallo del circuito (ver application.yml). Reintentar un 422 tres veces
 * da el mismo 422 tres veces, y ademas retrasa la respuesta honesta.
 */
public class PeticionRechazadaPorIAException extends RuntimeException {

    private final int codigoRemoto;

    public PeticionRechazadaPorIAException(int codigoRemoto, String mensaje) {
        super(mensaje);
        this.codigoRemoto = codigoRemoto;
    }

    public PeticionRechazadaPorIAException(int codigoRemoto, String mensaje,
                                           Throwable causa) {
        super(mensaje, causa);
        this.codigoRemoto = codigoRemoto;
    }

    /**
     * Codigo que devolvio Python (normalmente 422).
     *
     * Se conserva para diagnostico. No se expone al cliente: el 400 que
     * ve dice que su peticion no es valida, y WHICH codigo produjo el
     * rechazo es un detalle interno de la version de Python.
     */
    public int codigoRemoto() {
        return codigoRemoto;
    }
}
