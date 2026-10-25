package com.aula.asistencial.aulaassistentapi.exception;

import java.sql.SQLException;
import java.util.Map;

import org.hibernate.exception.ConstraintViolationException;
import org.postgresql.util.PSQLException;
import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(RecursoNoEncontradoException.class)
    public ProblemDetail recursoNoEncontrado(RecursoNoEncontradoException exception) {
        return problema(HttpStatus.NOT_FOUND, "Recurso no encontrado", exception.getMessage(),
                "RECURSO_NO_ENCONTRADO");
    }

    @ExceptionHandler(SolicitudInvalidaException.class)
    public ProblemDetail solicitudInvalida(SolicitudInvalidaException exception) {
        return problema(HttpStatus.BAD_REQUEST, "Solicitud inválida", exception.getMessage(), "DATOS_INVALIDOS");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail conflictoIntegridad(DataIntegrityViolationException exception) {
        var failure = falloBaseDatos(exception);
        if ("23P01".equals(failure.estado())) {
            var problem = switch (failure.restriccion() == null ? "" : failure.restriccion()) {
                case "no_cruce_espacio" -> problema(HttpStatus.CONFLICT, "Conflicto de horario",
                        "El espacio ya tiene una asignación que se cruza con ese horario.", "CRUCE_ESPACIO");
                case "no_cruce_docente" -> problema(HttpStatus.CONFLICT, "Conflicto de horario",
                        "El docente ya tiene una asignación que se cruza con ese horario.", "CRUCE_DOCENTE");
                default -> problema(HttpStatus.CONFLICT, "Conflicto de horario",
                        "El horario se cruza con una asignación existente.", "CRUCE_HORARIO");
            };
            if (failure.restriccion() != null) {
                problem.setProperty("restriccion", failure.restriccion());
            }
            return problem;
        }
        if ("23503".equals(failure.estado())) {
            return problema(HttpStatus.CONFLICT, "Recurso en uso",
                    "La operación entra en conflicto con las asignaciones o referencias existentes.",
                    "RECURSO_EN_USO");
        }
        if ("23514".equals(failure.estado()) || "23502".equals(failure.estado())
                || "22001".equals(failure.estado())) {
            return problema(HttpStatus.BAD_REQUEST, "Solicitud inválida",
                    "Los datos no cumplen las restricciones del recurso.", "DATOS_INVALIDOS");
        }
        return problema(HttpStatus.CONFLICT, "Conflicto de integridad",
                "La operación entra en conflicto con los datos existentes.", "CONFLICTO_INTEGRIDAD");
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        var problem = problema(HttpStatus.BAD_REQUEST, "Solicitud inválida",
                "Revisa los campos indicados.", "DATOS_INVALIDOS");
        problem.setProperty("errores", exception.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.of("campo", error.getField(), "mensaje",
                        error.getDefaultMessage() == null ? "Valor inválido" : error.getDefaultMessage()))
                .toList());
        return handleExceptionInternal(exception, problem, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException exception, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        var problem = problema(status, "Parámetros inválidos",
                "Los identificadores deben ser positivos y los parámetros deben ser válidos.", "DATOS_INVALIDOS");
        return handleExceptionInternal(exception, problem, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        var problem = problema(HttpStatus.BAD_REQUEST, "Solicitud inválida",
                "El JSON o sus tipos no son válidos. Usa fechas ISO con fecha y hora local.", "DATOS_INVALIDOS");
        return handleExceptionInternal(exception, problem, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        var problem = problema(HttpStatus.BAD_REQUEST, "Parámetros inválidos",
                "Usa identificadores enteros positivos y fechas ISO con fecha y hora local.", "DATOS_INVALIDOS");
        return handleExceptionInternal(exception, problem, headers, status, request);
    }

    private static ProblemDetail problema(HttpStatusCode status, String title, String detail, String codigo) {
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setProperty("codigo", codigo);
        return problem;
    }

    private static FalloBaseDatos falloBaseDatos(Throwable exception) {
        String estado = null;
        String restriccion = null;
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException constraint) {
                estado = constraint.getSQLState();
                restriccion = constraint.getConstraintName();
            }
            if (cause instanceof SQLException sql && sql.getSQLState() != null) {
                estado = sql.getSQLState();
            }
            // Hibernate puede omitir el nombre para 23P01. El servidor lo
            // proporciona como campo estructurado, independiente del idioma.
            if (cause instanceof PSQLException postgres && postgres.getServerErrorMessage() != null
                    && postgres.getServerErrorMessage().getConstraint() != null) {
                restriccion = postgres.getServerErrorMessage().getConstraint();
            }
        }
        return new FalloBaseDatos(estado, restriccion);
    }

    private record FalloBaseDatos(String estado, String restriccion) {
    }
}
