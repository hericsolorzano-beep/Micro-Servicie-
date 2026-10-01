package com.ejemplo.microservicio.repository;

import com.ejemplo.microservicio.domain.Usuario;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repositorio de usuarios.
 *
 * Spring Data genera la implementacion en tiempo de ejecucion a partir del
 * nombre. findByEmail se convierte en SELECT ... WHERE email = ?, sin
 * escribir SQL.
 *
 * JpaRepository trae save, findById, findAll y delete. save() hace un
 * INSERT si la id es null y un UPDATE si no lo es: no hay que decidir
 * cual de los dos.
 */
public interface UsuarioRepository extends JpaRepository<Usuario, Long> {

    Optional<Usuario> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);
}