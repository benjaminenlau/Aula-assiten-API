package com.aula.asistencial.aulaassistentapi.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LoginRateLimiter {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public LoginRateLimiter(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public boolean permitir(String remoteAddress) {
        String key;
        try {
            key = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(remoteAddress.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 no disponible", exception);
        }
        var now = clock.instant();
        jdbc.update("DELETE FROM limite_login WHERE ventana < ?", Timestamp.from(now.minus(1, ChronoUnit.HOURS)));
        var count = jdbc.queryForObject("""
                INSERT INTO limite_login (clave, ventana, intentos) VALUES (?, ?, 1)
                ON CONFLICT (clave) DO UPDATE SET
                    ventana = CASE WHEN limite_login.ventana <= ? THEN EXCLUDED.ventana ELSE limite_login.ventana END,
                    intentos = CASE WHEN limite_login.ventana <= ? THEN 1 ELSE limite_login.intentos + 1 END
                RETURNING intentos
                """, Integer.class, key, Timestamp.from(now),
                Timestamp.from(now.minusSeconds(60)), Timestamp.from(now.minusSeconds(60)));
        return count != null && count <= 30;
    }
}
