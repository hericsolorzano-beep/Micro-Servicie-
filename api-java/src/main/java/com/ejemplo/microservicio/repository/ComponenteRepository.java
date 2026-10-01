package com.ejemplo.microservicio.repository;

import com.ejemplo.microservicio.domain.Componente;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repositorio de componentes PCA.
 *
 * saveAll existe en JpaRepository y hace un INSERT multiple o en lote,
 * segun la configuracion de Hibernate. Para 28 filas por transaccion,
 * mejor que 28.save() sueltos.
 */
public interface ComponenteRepository extends JpaRepository<Componente, Componente.Clave> {
}