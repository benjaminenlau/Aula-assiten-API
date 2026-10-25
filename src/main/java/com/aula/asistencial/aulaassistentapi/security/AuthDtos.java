package com.aula.asistencial.aulaassistentapi.security;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class AuthDtos {

    private AuthDtos() {
    }

    public enum Rol { ADMIN, PROFESIONAL }

    public record Usuario(long id, String usuario, String nombre, Rol rol, boolean activo) {
    }

    public record Principal(long id, String usuario, String nombre, Rol rol) {
    }

    public record LoginRequest(
            @NotBlank @Size(max = 60) String usuario,
            @NotBlank @Size(max = 128) String password) {
        @Override
        public String toString() { return "LoginRequest[credenciales ocultas]"; }
    }

    public record LoginResponse(String token, String tipo, Instant expiraEn, Usuario usuario) {
        @Override
        public String toString() { return "LoginResponse[token oculto, usuario=" + usuario.usuario() + "]"; }
    }

    public record CrearUsuarioRequest(
            @NotBlank @Pattern(regexp = "[a-zA-Z0-9._-]{3,60}",
                    message = "Usa entre 3 y 60 letras, numeros, puntos, guiones o guiones bajos.") String usuario,
            @NotBlank @Size(max = 160) String nombre,
            @NotBlank @Size(min = 12, max = 128,
                    message = "La contrasena debe tener entre 12 y 128 caracteres.") String password,
            @NotNull Rol rol) {
        @Override
        public String toString() { return "CrearUsuarioRequest[credenciales ocultas]"; }
    }

    public record CambiarPasswordRequest(
            @NotBlank @Size(min = 12, max = 128,
                    message = "La contrasena debe tener entre 12 y 128 caracteres.") String password) {
        @Override
        public String toString() { return "CambiarPasswordRequest[credenciales ocultas]"; }
    }

    public record EstadoUsuarioRequest(@NotNull Boolean activo) {
    }
}
