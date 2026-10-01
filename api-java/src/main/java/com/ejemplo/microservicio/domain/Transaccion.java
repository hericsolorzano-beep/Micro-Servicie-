package com.ejemplo.microservicio.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Transaccion analizada por el servicio de IA.
 *
 * La relacion con Usuario es LAZY por defecto en JPA, y conviene: al
 * listar 100 transacciones no interesa cargar 100 usuarios. El proxy se
 * resuelve solo cuando se llama a getUsuario(), dentro de la transaccion.
 * Fuera de ella, LazyInitializationException: ese es el precio, y por eso
 * el service mapea a DTO dentro de la transaccion.
 */
@Entity
@Table(name = "transaccion")
public class Transaccion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "usuario_id", nullable = false)
    private Usuario usuario;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal monto;

    @Column(nullable = false)
    private Integer hora;

    @Column(nullable = false, length = 2)
    private String pais;

    @Column(name = "distancia_km", nullable = false, precision = 10, scale = 1)
    private BigDecimal distanciaKm;

    /**
     * Nº de componentes PCA que se enviaron al modelo.
     *
     * Se guarda porque el modelo exige un numero exacto: si en el futuro
     * cambia a 30 componentes, el dato historico sigue siendo valido con
     * su 28. Sin este campo, una transaccion vieja no se podria
     * reevaluar con el modelo nuevo sin adivinar.
     */
    @Column(name = "numero_componentes", nullable = false)
    private Integer numeroComponentes;

    // Campos del veredicto. Todos nulables a proposito: una transaccion
    // se guarda AUNQUE la IA no estuviera disponible. Perder el registro
    // de lo que no se pudo evaluar seria peor que guardarlo sin veredicto.

    /** @see #errorAnalisis para la diferencia con null. */
    @Column(name = "es_fraude")
    private Boolean esFraude;

    @Column(precision = 5, scale = 4)
    private BigDecimal probabilidad;

    @Column(name = "nivel_riesgo", length = 20)
    private String nivelRiesgo;

    @Column(length = 60)
    private String modelo;

    @Column(length = 30)
    private String accion;

    /**
     * Umbral con el que el servicio de IA decidio el veredicto.
     *
     * Sin guardarlo, el historial no permite reconstruir por que una
     * transaccion de hace seis meses se aprobo o se bloqueo: el
     * umbral pudo haber cambiado desde entonces.
     *
     * Sin precision/scale a proposito: la columna es REAL, un tipo de
     * coma flotante, y Hibernate rechaza la escala en esos tipos
     * ("scale has no meaning for SQL floating point types"). El error
     * sale al arrancar, no al escribir, asi que es facil no verlo.
     */
    @Column
    private Double umbral;

    /**
     * Motivo por el que no se pudo analizar, si la IA fallo.
     *
     * Es la distincion importante del modelo: es_fraude NULL significa
     * "no evaluado", que NO es lo mismo que es_fraude FALSE ("evaluado y
     * limpio"). Confundirlas seria un fallo de seguridad: una transaccion
     * que nadie evaluo pareceria una transaccion aprobada.
     */
    @Column(name = "error_analisis", length = 100)
    private String errorAnalisis;

    @Column(name = "tiempo_inferencia_ms")
    private Long tiempoInferenciaMs;

    @Column(name = "creado_en", nullable = false)
    private Instant creadoEn = Instant.now();

    protected Transaccion() {
    }

    public Transaccion(Usuario usuario, BigDecimal monto, Integer hora,
                      String pais, BigDecimal distanciaKm,
                      Integer numeroComponentes) {
        this.usuario = usuario;
        this.monto = monto;
        this.hora = hora;
        this.pais = pais;
        this.distanciaKm = distanciaKm;
        this.numeroComponentes = numeroComponentes;
        this.creadoEn = Instant.now();
    }

    /** Registra el veredicto del modelo. */
    public void registrarVeredicto(Boolean esFraude, BigDecimal probabilidad,
                                   String nivelRiesgo, String modelo,
                                   String accion, Long tiempoMs,
                                   Double umbral) {
        this.esFraude = esFraude;
        this.probabilidad = probabilidad;
        this.nivelRiesgo = nivelRiesgo;
        this.modelo = modelo;
        this.accion = accion;
        this.tiempoInferenciaMs = tiempoMs;
        this.umbral = umbral;
        this.errorAnalisis = null;
    }

    /** Registra que la IA no estuvo disponible. */
    public void registrarErrorAnalisis(String error) {
        this.esFraude = null;
        this.probabilidad = null;
        this.nivelRiesgo = null;
        this.accion = null;
        this.errorAnalisis = error;
    }

    public boolean fueAnalizada() {
        return esFraude != null;
    }

    public Long getId() {
        return id;
    }

    public Usuario getUsuario() {
        return usuario;
    }

    public BigDecimal getMonto() {
        return monto;
    }

    public Integer getHora() {
        return hora;
    }

    public String getPais() {
        return pais;
    }

    public BigDecimal getDistanciaKm() {
        return distanciaKm;
    }

    public Boolean getEsFraude() {
        return esFraude;
    }

    public BigDecimal getProbabilidad() {
        return probabilidad;
    }

    public String getNivelRiesgo() {
        return nivelRiesgo;
    }

    public String getModelo() {
        return modelo;
    }

    public String getAccion() {
        return accion;
    }

    public Integer getNumeroComponentes() {
        return numeroComponentes;
    }

    public Double getUmbral() {
        return umbral;
    }

    public String getErrorAnalisis() {
        return errorAnalisis;
    }

    public Long getTiempoInferenciaMs() {
        return tiempoInferenciaMs;
    }

    public Instant getCreadoEn() {
        return creadoEn;
    }
}