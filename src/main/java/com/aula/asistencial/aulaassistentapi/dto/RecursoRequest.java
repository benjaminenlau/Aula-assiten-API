package com.aula.asistencial.aulaassistentapi.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RecursoRequest(
        @NotBlank(message = "El nombre es obligatorio.")
        @Size(max = 255, message = "El nombre admite como máximo 255 caracteres.") String nombre) {
}
