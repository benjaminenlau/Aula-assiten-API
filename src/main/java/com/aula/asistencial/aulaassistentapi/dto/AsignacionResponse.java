package com.aula.asistencial.aulaassistentapi.dto;

import java.time.LocalDateTime;

public record AsignacionResponse(
        Long id,
        Long espacioId,
        Long docenteId,
        LocalDateTime inicio,
        LocalDateTime fin) {
}
