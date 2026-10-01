package com.ejemplo.microservicio.domain;

import com.ejemplo.microservicio.repository.ComponenteRepository;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Una componente PCA de una transaccion.
 *
 * Tabla hija: una fila por componente, con clave primaria compuesta
 * (transaccion_id, posicion). Esa clave hace que no pueda haber dos
 * componentes en la misma posicion sin que la base lo impida, que es
 * exactamente la garantia que queremos.
 *
 * Por que en tabla aparte y no 28 columnas en transaccion: llenarian la
 * tabla principal de numeros que casi nadie consulta, y una fila por
 * componente permite validar la longitud con una consulta en vez de
 * con triggers.
 */
@Entity
@Table(name = "transaccion_componente")
// @IdClass es OBLIGATORIA con clave compuesta. Sin ella, JPA avisa
// literally "This class [Componente] does not define an IdClass" y el
// contexto no arranca. El record Clave de abajo es esa clase.
@IdClass(Componente.Clave.class)
public class Componente {

    /**
     * Clave primaria COMPUESTA.
     *
     * JPA admite varios @Id: los campos forman la clave juntos. Es lo que
     * quiere el esquema, porque la posicion sola no identifica nada
     * (la misma posicion existe en todas las transacciones).
     */
    @Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transaccion_id", nullable = false)
    private Transaccion transaccion;

    /** 0-based: 0 es V1, 27 es V28. */
    @Id
    @Column(name = "posicion", nullable = false)
    private Short posicion;

    @Column(name = "valor", nullable = false, precision = 12, scale = 8)
    private BigDecimal valor;

    protected Componente() {
    }

    public Componente(Transaccion transaccion, Short posicion, BigDecimal valor) {
        this.transaccion = transaccion;
        this.posicion = posicion;
        this.valor = valor;
    }

    public Transaccion getTransaccion() {
        return transaccion;
    }

    public Short getPosicion() {
        return posicion;
    }

    public BigDecimal getValor() {
        return valor;
    }

    /**
     * Clave compuesta (transaccion_id, posicion).
     *
     * JpaRepository necesita un tipo para el id. Como la clave son dos
     * columnas, se declara este record embebido: implements Serializable
     * es requisito de JPA para claves compuestas, y equals/hashCode
     *cubren lo que Spring usa para localizar la entidad en la sesion.
     */
    /**
     * Clave compuesta (transaccion_id, posicion).
     *
     * JpaRepository necesita un TIPO para el id, y @IdClass necesita el
     * nombre de una clase. Con dos columnas en la clave, ese tipo tiene
     * que existir.
     *
     * implements Serializable: requisito de JPA para claves compuestas.
     * Sin el, Hibernate falla al arrancar.
     *
     * Los NOMBRES de los atributos deben coincidir con los de los
     * campos @Id de la entidad (transaccion, posicion). Si difieren, el
     * error menciona un atributo inexistente.
     *
     * Una CLASE y no un record a proposito: @IdClass necesita una clase
     * con constructor sin argumentos. Ademas, los records con tipos
     * complexos hacen que Hibernate mapee la entidad entera como
     * VARBINARY en vez de por su id, y el fallo aparece como
     * "found [int8], but expecting [bytea]": del todo desconcertante
     * cuando la causa es que se uso un record.
     */
    public static class Clave implements java.io.Serializable {

        private Transaccion transaccion;
        private Short posicion;

        /** Lo necesita JPA para instanciar la clave por reflexion. */
        public Clave() {
        }

        public Clave(Transaccion transaccion, Short posicion) {
            this.transaccion = transaccion;
            this.posicion = posicion;
        }

        public Transaccion getTransaccion() {
            return transaccion;
        }

        public Short getPosicion() {
            return posicion;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Clave otra)) {
                return false;
            }
            // Por id, no por identidad de objeto: dos instancias de la
            // misma fila deben producir la misma clave.
            Long idA = transaccion == null ? null : transaccion.getId();
            Long idB = otra.transaccion == null ? null : otra.transaccion.getId();
            return java.util.Objects.equals(idA, idB)
                    && java.util.Objects.equals(posicion, otra.posicion);
        }

        @Override
        public int hashCode() {
            Long id = transaccion == null ? null : transaccion.getId();
            return java.util.Objects.hash(id, posicion);
        }
    }

    /**
     * Convierte una lista suelta de componentes en entidades y las guarda.
     *
     * Se hace en Lote (saveAll) y no de uno en uno: son 28 filas por
     * transaccion, y 28 INSERT sueltos por peticion es una carga
     * innecesaria sobre la base.
     */
    public static void guardarTodas(
            ComponenteRepository repositorio,
            Transaccion transaccion,
            List<Double> valores) {

        if (valores == null || valores.isEmpty()) {
            return;
        }

        List<Componente> entidades = new ArrayList<>(valores.size());
        for (int i = 0; i < valores.size(); i++) {
            Double v = valores.get(i);
            if (v == null) {
                continue;
            }
            entidades.add(new Componente(
                    transaccion, (short) i, BigDecimal.valueOf(v)));
        }
        repositorio.saveAll(entidades);
    }
}