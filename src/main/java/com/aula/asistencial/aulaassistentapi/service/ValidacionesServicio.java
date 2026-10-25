package com.aula.asistencial.aulaassistentapi.service;

import java.time.LocalDateTime;

import com.aula.asistencial.aulaassistentapi.exception.SolicitudInvalidaException;

final class ValidacionesServicio {

    private ValidacionesServicio() {
    }

    static void idPositivo(Long id, String campo) {
        if (id == null || id <= 0) {
            throw new SolicitudInvalidaException(campo + " debe ser un entero positivo.");
        }
    }

    static void intervalo(LocalDateTime inicio, LocalDateTime fin, String campoInicio, String campoFin) {
        if (inicio == null || fin == null) {
            throw new SolicitudInvalidaException(campoInicio + " y " + campoFin + " son obligatorios.");
        }
        if (!inicio.isBefore(fin)) {
            throw new SolicitudInvalidaException(campoInicio + " debe ser anterior a " + campoFin + ".");
        }
        if (inicio.getNano() % 1_000 != 0 || fin.getNano() % 1_000 != 0) {
            throw new SolicitudInvalidaException(
                    campoInicio + " y " + campoFin + " admiten como maximo seis decimales de segundo.");
        }
    }
}
