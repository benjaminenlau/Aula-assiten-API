package com.aula.asistencial.aulaassistentapi.repository;

import com.aula.asistencial.aulaassistentapi.entity.Docente;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocenteRepository extends JpaRepository<Docente, Long> {
}
