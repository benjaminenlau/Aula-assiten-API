package com.aula.asistencial.aulaassistentapi.security;

import java.io.IOException;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class SecurityProblemWriter {

    private final JsonMapper json;

    public SecurityProblemWriter(JsonMapper json) {
        this.json = json;
    }

    public void write(HttpServletRequest request, HttpServletResponse response,
            int status, String codigo, String detail) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader("Cache-Control", "no-store");
        if (status == 401) response.setHeader("WWW-Authenticate", "Bearer");
        json.writeValue(response.getOutputStream(), Map.of(
                "type", "about:blank", "status", status, "codigo", codigo,
                "title", status == 403 ? "Acceso denegado" : status == 429 ? "Demasiados intentos" : "Autenticacion requerida",
                "detail", detail, "instance", request.getRequestURI()));
    }
}
