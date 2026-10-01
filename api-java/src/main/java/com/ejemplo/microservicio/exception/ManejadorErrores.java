package com.ejemplo.microservicio.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Manejo centralizado de errores.
 *
 * @RestControllerAdvice aplica a todos los controllers a la vez. Sin el,
 * cada endpoint tendria que repetir su propio try/catch, y tarde o
 * temprano uno se olvida.
 *
 * Decidir el status HTTP aqui es una decision de diseno: el servicio de
 * negocio lanza excepciones en Java plano, y esta capa las traduce al
 * contrato HTTP. El dominio no necesita saber que existe HTTP.
 *
 * Importante: extiende ResponseEntityExceptionHandler. Sin esa herencia,
 * un handler de Exception declarado aqui gana a los resolvers internos de
 * Spring, y rutas inexistentes o metodos no permitidos pasan a devolver
 * 500 en vez de su 404/405 correcto. Heredando, Spring mantiene el orden
 * de resolucion y nosotros solo anadimos los casos propios.
 */
@RestControllerAdvice
public class ManejadorErrores extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ManejadorErrores.class);

/**
 * Acceso denegado a un recurso.
 *
 * Devuelve 404 y no 403 a proposito: un 403 confirma que el recurso
 * EXISTE pero que no es tuyo, y eso permite enumerar usuarios validos
 * probando identificadores. El 404 no revela nada.
 *
 * Ver AccesoDenegadoException.comoSiNoExistiera().
 */
@ExceptionHandler(com.ejemplo.microservicio.exception.AccesoDenegadoException.class)
public ResponseEntity<RespuestaError> accesoDenegado(
        com.ejemplo.microservicio.exception.AccesoDenegadoException e) {

    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            RespuestaError.de("NO_ENCONTRADO", e.getMessage()));
}

/**
 * El usuario indicado no existe: 404, no 400 ni 500.
 */
@ExceptionHandler(com.ejemplo.microservicio.exception.UsuarioNoEncontradoException.class)
public ResponseEntity<RespuestaError> usuarioNoEncontrado(
        com.ejemplo.microservicio.exception.UsuarioNoEncontradoException e) {

    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            RespuestaError.de("USUARIO_NO_ENCONTRADO", e.getMessage()));
}

/**
 * Email ya registrado.
 *
 * 409 y no 400: el conflicto no es un dato invalido, es un estado que
 * colisiona con otro recurso existente. El 409 es lo que permite al
 * cliente distinguir "corrigeme esto" de "ya existe".
 */
@ExceptionHandler(IllegalArgumentException.class)
public ResponseEntity<RespuestaError> conflictoDeNegocio(IllegalArgumentException e) {

    return ResponseEntity.status(HttpStatus.CONFLICT).body(
            RespuestaError.de("CONFLICTO", e.getMessage()));
}

    /**
     * Errores de validacion de @Valid: el cliente mando datos invalidos.
     *
     * Se sobrescribe con @Override y NO lleva @ExceptionHandler: la clase
     * padre ResponseEntityExceptionHandler ya declara un handler para esta
     * excepcion. Declarar otro aqui produce un conflicto de mapeo y el
     * contexto ni siquiera arranca. La anotacion @Override documenta
     * ademas que estamos量身ando el comportamiento del padre.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException e,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {

        java.util.Map<String, String> detalles = new java.util.HashMap<>();
        e.getBindingResult().getFieldErrors().forEach(error ->
                detalles.putIfAbsent(error.getField(), error.getDefaultMessage()));

        RespuestaError cuerpo = new RespuestaError("VALIDACION",
                "Los datos enviados no son validos", detalles);

        return new ResponseEntity<>(cuerpo, headers, HttpStatus.BAD_REQUEST);
    }

    /**
     * El servicio de IA no respondio.
     *
     * 503 es lo correcto: el problema no es la peticion del cliente, es
     * nuestra dependencia. Un 500 diria al cliente que el error es suyo y
     * no tiene sentido que reintente el mismo payload.
     */
    @ExceptionHandler(ServicioIANoDisponibleException.class)
    public ResponseEntity<RespuestaError> iaNoDisponible(ServicioIANoDisponibleException e) {
        log.error("Servicio de IA no disponible: {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
                RespuestaError.de("IA_NO_DISPONIBLE",
                        "El servicio de analisis no esta disponible en este momento"));
    }

    /**
     * JSON mal formado: el cuerpo no se puede deserializar.
     *
     * Sobrescrito sin @ExceptionHandler por el mismo motivo que el caso
     * de validacion: el padre ya lo declara. Spring lo convertiria en 400
     * igualmente, pero con una estructura de error distinta; aqui se
     * unifica el formato para que el cliente siempre reciba
     * {codigo, mensaje, detalles}.
     */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            org.springframework.http.converter.HttpMessageNotReadableException e,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {

        log.warn("Cuerpo de peticion ilegible: {}", e.getMessage());
        return new ResponseEntity<>(
                RespuestaError.de("VALIDACION",
                        "El cuerpo de la peticion no es JSON valido"),
                headers, HttpStatus.BAD_REQUEST);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMediaTypeNotSupported(
            org.springframework.web.HttpMediaTypeNotSupportedException e,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {

        return new ResponseEntity<>(
                RespuestaError.de("TIPO_NO_SOPORTADO",
                        "El endpoint solo acepta application/json"),
                headers, HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }
    

    /**
     * El circuito esta abierto: la llamada se rechaza sin tocar la red.
     *
     * Sin este handler, CallNotPermittedException cae en el catch-all y el
     * cliente recibe un 500. Eso esta mal por dos razones: un 500 dice "tenemos
     * un fallo de codigo" cuando lo que hay es una proteccion funcionando
     * como se diseñó, y lo oculta del balanceador, que dejaria de mandar
     * trafico a una instancia que no puede atender.
     *
     * Devuelve 503 con un mensaje propio que explica que es un problema
     * temporal, que es exactamente lo que es.
     */
    @ExceptionHandler({
            io.github.resilience4j.circuitbreaker.CallNotPermittedException.class,
            io.github.resilience4j.bulkhead.BulkheadFullException.class
    })
    public ResponseEntity<RespuestaError> proteccionResiliencia(RuntimeException e) {

        boolean esCircuito = e instanceof
                io.github.resilience4j.circuitbreaker.CallNotPermittedException;

        log.warn("Peticion rechazada por {}: {}",
                esCircuito ? "circuito abierto" : "bulkhead lleno", e.getMessage());

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
                RespuestaError.de(
                        esCircuito ? "CIRCUITO_ABIERTO" : "SERVICIO_SATURADO",
                        esCircuito
                                ? "El servicio de analisis no esta disponible. Reintentare en unos segundos"
                                : "El servicio de analisis esta saturado. Reintentare en unos segundos"));
    }

    /**
     * Red de seguridad para lo que no se haya previsto.
     *
     * Spring evalua los handlers de la clase padre ANTES que este, de
     * modo que las excepciones propias de MVC (404, 405, 415...) conservan
     * su status correcto. Este solo captura lo que nadie mas reclama.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<RespuestaError> inesperado(Exception e) {
        log.error("Error no controlado", e);
        // El cliente recibe un mensaje generico; la traza va al log, nunca
        // a la respuesta: filtrarla revela rutas y versiones de libreria.
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                RespuestaError.de("ERROR_INTERNO",
                        "Ocurrio un error inesperado al procesar la solicitud"));
    }
}