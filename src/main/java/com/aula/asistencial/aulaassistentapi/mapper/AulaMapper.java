package com.aula.asistencial.aulaassistentapi.mapper;

import com.aula.asistencial.aulaassistentapi.dto.AsignacionResponse;
import com.aula.asistencial.aulaassistentapi.dto.DocenteResponse;
import com.aula.asistencial.aulaassistentapi.dto.EspacioResponse;
import com.aula.asistencial.aulaassistentapi.entity.Asignacion;
import com.aula.asistencial.aulaassistentapi.entity.Docente;
import com.aula.asistencial.aulaassistentapi.entity.Espacio;

public final class AulaMapper {

    private AulaMapper() {
    }

    public static EspacioResponse toResponse(Espacio espacio) {
        return new EspacioResponse(espacio.getId(), espacio.getNombre());
    }

    public static DocenteResponse toResponse(Docente docente) {
        return new DocenteResponse(docente.getId(), docente.getNombre());
    }

    public static AsignacionResponse toResponse(Asignacion asignacion) {
        return new AsignacionResponse(
                asignacion.getId(),
                asignacion.getEspacio().getId(),
                asignacion.getDocente().getId(),
                asignacion.getInicio(),
                asignacion.getFin());
    }
}
