package com.aula.asistencial.aulaassistentapi.security;

import org.springframework.http.CacheControl;
import org.springframework.http.ProblemDetail;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class AuthExceptionHandler {

    @ExceptionHandler(AuthException.class)
    public ResponseEntity<ProblemDetail> authenticationFailure(AuthException exception) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(exception.getStatus()), exception.getMessage());
        problem.setTitle("Solicitud de acceso rechazada");
        problem.setProperty("codigo", exception.getCodigo());
        var response = ResponseEntity.status(exception.getStatus()).cacheControl(CacheControl.noStore());
        if (exception.getStatus() == 401) response.header("WWW-Authenticate", "Bearer");
        return response.body(problem);
    }
}
