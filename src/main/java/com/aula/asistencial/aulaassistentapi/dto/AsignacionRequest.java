package com.aula.asistencial.aulaassistentapi.dto;

import java.time.LocalDateTime;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record AsignacionRequest(
        @NotNull(message = "El espacio es obligatorio.")
        @Positive(message = "El identificador del espacio debe ser positivo.") Long espacioId,
        @NotNull(message = "El docente es obligatorio.")
        @Positive(message = "El identificador del docente debe ser positivo.") Long docenteId,
        @NotNull(message = "El inicio es obligatorio.") LocalDateTime inicio,
        @NotNull(message = "El fin es obligatorio.") LocalDateTime fin) {
}
