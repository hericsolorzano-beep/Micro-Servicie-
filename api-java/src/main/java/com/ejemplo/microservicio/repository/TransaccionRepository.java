package com.ejemplo.microservicio.repository;

import com.ejemplo.microservicio.domain.Transaccion;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repositorio de transacciones.
 *
 * Spring Data deduce las consultas por el nombre del metodo. El Page del
 * ultimo parametro no es un adorno: activa la paginacion, y sin ella
 * "dame todas las transacciones del usuario" carga la tabla entera en
 * memoria. Es la causa numero uno de OOM en servicios con histórico.
 */
public interface TransaccionRepository extends JpaRepository<Transaccion, Long> {

    /**
     * Pageable con orden explicito por fecha descendente. El indice
     * (usuario_id, creado_en DESC) cubre esta consulta sin ordenar todo.
     */
    List<Transaccion> findByUsuarioIdOrderByCreadoEnDesc(
            Long usuarioId, Pageable pageable);

    List<Transaccion> findByUsuarioIdAndEsFraudeTrueOrderByCreadoEnDesc(
            Long usuarioId, Pageable pageable);

    long countByUsuarioId(Long usuarioId);

    long countByUsuarioIdAndEsFraudeTrue(Long usuarioId);

    /**
     * Consulta agrupada: cuanto ha movido cada usuario y cuantas
     * detecciones ha tenido.
     *
     * Se escribe en JPQL y no en SQL nativo a proposito. JPQL es portable
     * (funciona igual en H2 y en PostgreSQL), mientras que el SQL nativo
     * obliga a reescribirlo para los tests. El coste es que JPQL no tiene
     * funciones tan ricas, y para eso estan los repositorios
     * personalizados.
     */
    @Query("""
            SELECT t.usuario.id, COUNT(t), COALESCE(SUM(t.monto), 0)
            FROM Transaccion t
            GROUP BY t.usuario.id
            """)
    List<Object[]> resumenPorUsuario();

    @Query("""
            SELECT t FROM Transaccion t
            WHERE t.usuario.id = :usuarioId
              AND (:soloFraude = false OR t.esFraude = true)
            ORDER BY t.creadoEn DESC
            """)
    List<Transaccion> buscar(
            @Param("usuarioId") Long usuarioId,
            @Param("soloFraude") boolean soloFraude,
            Pageable pageable);
}