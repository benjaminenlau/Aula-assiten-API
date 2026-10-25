package com.aula.asistencial.aulaassistentapi.security;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class BearerAuthenticationFilter extends OncePerRequestFilter {

    private final AuthService auth;
    private final LoginRateLimiter limiter;
    private final SecurityProblemWriter problems;

    public BearerAuthenticationFilter(AuthService auth, LoginRateLimiter limiter, SecurityProblemWriter problems) {
        this.auth = auth;
        this.limiter = limiter;
        this.problems = problems;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if ("POST".equals(request.getMethod()) && "/api/auth/login".equals(request.getServletPath())) {
            // No se confia en X-Forwarded-For suministrado por el cliente.
            if (!limiter.permitir(request.getRemoteAddr())) {
                response.setHeader("Retry-After", "60");
                problems.write(request, response, 429, "DEMASIADOS_INTENTOS",
                        "Espera un minuto antes de volver a intentar iniciar sesion.");
                return;
            }
            chain.doFilter(request, response);
            return;
        }
        var header = request.getHeader("Authorization");
        if (header == null) {
            chain.doFilter(request, response);
            return;
        }
        if (!header.matches("(?i)Bearer [A-Za-z0-9_-]{43}")) {
            problems.write(request, response, 401, "NO_AUTENTICADO", "La sesion no es valida o ha caducado.");
            return;
        }
        var principal = auth.authenticate(header.substring(7));
        if (principal == null) {
            problems.write(request, response, 401, "NO_AUTENTICADO", "La sesion no es valida o ha caducado.");
            return;
        }
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + principal.rol().name()))));
        SecurityContextHolder.setContext(context);
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
