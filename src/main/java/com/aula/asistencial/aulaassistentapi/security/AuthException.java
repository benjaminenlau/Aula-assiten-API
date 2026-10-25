package com.aula.asistencial.aulaassistentapi.security;

public class AuthException extends RuntimeException {

    private final int status;
    private final String codigo;

    public AuthException(int status, String codigo, String detail) {
        super(detail);
        this.status = status;
        this.codigo = codigo;
    }

    public int getStatus() { return status; }
    public String getCodigo() { return codigo; }
}
