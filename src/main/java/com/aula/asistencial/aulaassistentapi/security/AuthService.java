package com.aula.asistencial.aulaassistentapi.security;

import static com.aula.asistencial.aulaassistentapi.security.AuthDtos.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private static final Logger LOG = LoggerFactory.getLogger(AuthService.class);
    private static final Duration DURACION_SESION = Duration.ofHours(8);
    private static final Duration INACTIVIDAD_MAXIMA = Duration.ofMinutes(30);
    private static final Duration DURACION_BLOQUEO = Duration.ofMinutes(15);
    private static final int INTENTOS_MAXIMOS = 5;
    private static final long BLOQUEO_ADMIN = 0x41554c4141555448L;
    private static final Pattern USUARIO_VALIDO = Pattern.compile("[a-z0-9._-]{3,60}");
    private static final Pattern TOKEN_VALIDO = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final String COLUMNAS_USUARIO = "id, usuario, nombre, rol, activo";
    private static final String COLUMNAS_CREDENCIALES = COLUMNAS_USUARIO
            + ", password_hash, intentos_fallidos, bloqueado_hasta";

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final String dummyPasswordHash;

    public AuthService(JdbcTemplate jdbc, PasswordEncoder passwordEncoder, Clock clock) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        // Igualar el coste del hash cuando el nombre de usuario no existe.
        this.dummyPasswordHash = passwordEncoder.encode(nuevoToken());
    }

    @Transactional(noRollbackFor = AuthException.class)
    public LoginResponse login(String usuario, String password) {
        String normalizado = normalizarUsuario(usuario);
        Credenciales cuenta = USUARIO_VALIDO.matcher(normalizado).matches()
                ? buscarPorUsuarioParaActualizar(normalizado) : null;
        String passwordSeguro = password != null && password.length() <= 128 ? password : "";
        boolean passwordCorrecto = passwordEncoder.matches(passwordSeguro,
                cuenta == null ? dummyPasswordHash : cuenta.passwordHash());
        Instant ahora = clock.instant();
        if (cuenta == null || !cuenta.usuario().activo()
                || (cuenta.bloqueadoHasta() != null && cuenta.bloqueadoHasta().isAfter(ahora))) {
            throw credencialesInvalidas();
        }
        if (!passwordCorrecto) {
            int intentos = cuenta.bloqueadoHasta() != null ? 1
                    : Math.min(cuenta.intentosFallidos() + 1, INTENTOS_MAXIMOS);
            Instant bloqueo = intentos >= INTENTOS_MAXIMOS ? ahora.plus(DURACION_BLOQUEO) : null;
            jdbc.update("UPDATE usuario_app SET intentos_fallidos = ?, bloqueado_hasta = ? WHERE id = ?",
                    intentos, timestamp(bloqueo), cuenta.usuario().id());
            if (bloqueo != null) {
                LOG.warn("Cuenta bloqueada temporalmente por intentos fallidos: usuarioId={}", cuenta.usuario().id());
            }
            throw credencialesInvalidas();
        }

        Sesion anterior = buscarSesionParaActualizar(cuenta.usuario().id());
        // La contraseña se comprueba antes de revelar si existe una sesión.
        ahora = clock.instant();
        if (anterior != null && sesionVigente(anterior, ahora)) {
            throw new AuthException(409, "SESION_ACTIVA",
                    "El usuario ya tiene una sesión activa. Cierre esa sesión o espere su vencimiento.");
        }
        String token = nuevoToken();
        Instant expira = ahora.plus(DURACION_SESION);
        jdbc.update("""
                INSERT INTO sesion_usuario (usuario_id, token_hash, creada_en, ultima_actividad, expira_en)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (usuario_id) DO UPDATE SET token_hash = EXCLUDED.token_hash,
                    creada_en = EXCLUDED.creada_en, ultima_actividad = EXCLUDED.ultima_actividad,
                    expira_en = EXCLUDED.expira_en
                """, cuenta.usuario().id(), hashToken(token), timestamp(ahora), timestamp(ahora), timestamp(expira));
        jdbc.update("UPDATE usuario_app SET intentos_fallidos = 0, bloqueado_hasta = NULL WHERE id = ?",
                cuenta.usuario().id());
        LOG.info("Sesión iniciada: usuarioId={}", cuenta.usuario().id());
        return new LoginResponse(token, "Bearer", expira, cuenta.usuario());
    }

    @Transactional
    public Principal authenticate(String rawToken) {
        String hash = hashTokenValido(rawToken);
        if (hash == null) {
            return null;
        }
        Long id = buscarUsuarioDeToken(hash);
        if (id == null) {
            return null;
        }
        // Todos los flujos bloquean usuario antes que sesión, evitando interbloqueos.
        Credenciales cuenta = buscarPorIdParaActualizar(id);
        Sesion sesion = buscarSesionParaActualizar(id);
        if (cuenta == null || sesion == null || !hash.equals(sesion.tokenHash())) {
            return null;
        }
        Instant ahora = clock.instant();
        if (!cuenta.usuario().activo() || !sesionVigente(sesion, ahora)) {
            revocarSesion(id, hash);
            return null;
        }
        int actualizadas = jdbc.update("""
                UPDATE sesion_usuario SET ultima_actividad = GREATEST(ultima_actividad, ?)
                WHERE usuario_id = ? AND token_hash = ?
                """, timestamp(ahora), id, hash);
        if (actualizadas != 1) {
            return null;
        }
        Usuario usuario = cuenta.usuario();
        return new Principal(usuario.id(), usuario.usuario(), usuario.nombre(), usuario.rol());
    }

    @Transactional
    public void logout(String rawToken) {
        String hash = hashTokenValido(rawToken);
        if (hash == null) {
            return;
        }
        Long id = buscarUsuarioDeToken(hash);
        if (id == null || buscarPorIdParaActualizar(id) == null) {
            return;
        }
        // Una petición antigua nunca puede revocar una sesión que la haya reemplazado.
        revocarSesion(id, hash);
        LOG.info("Sesión cerrada: usuarioId={}", id);
    }

    @Transactional(readOnly = true)
    public List<Usuario> listarUsuarios() {
        return jdbc.query("SELECT " + COLUMNAS_USUARIO + " FROM usuario_app ORDER BY id", AuthService::mapUsuario);
    }

    @Transactional
    public Usuario crearUsuario(CrearUsuarioRequest request) {
        if (request == null) {
            throw solicitudInvalida("Los datos del usuario son obligatorios.");
        }
        String usuario = validarUsuario(request.usuario());
        String nombre = validarNombre(request.nombre());
        validarPassword(request.password());
        if (request.rol() == null) {
            throw solicitudInvalida("El rol es obligatorio.");
        }
        String passwordHash = passwordEncoder.encode(request.password());
        bloquearAdministracion();
        Usuario creado = insertarUsuario(usuario, nombre, passwordHash, request.rol());
        if (creado == null) {
            throw new AuthException(409, "USUARIO_EXISTENTE", "Ya existe un usuario con ese nombre de acceso.");
        }
        LOG.info("Usuario creado: usuarioId={}, rol={}", creado.id(), creado.rol());
        return creado;
    }

    @Transactional
    public Usuario cambiarEstado(long actorId, long id, boolean activo) {
        bloquearAdministracion();
        Credenciales cuenta = requerirUsuario(id);
        if (actorId == id && !activo) {
            throw new AuthException(403, "AUTO_DESACTIVACION", "No puede desactivar su propia cuenta.");
        }
        if (!activo && cuenta.usuario().activo() && cuenta.usuario().rol() == Rol.ADMIN
                && contarAdministradoresActivos() <= 1) {
            throw new AuthException(409, "ULTIMO_ADMINISTRADOR", "Debe permanecer al menos un administrador activo.");
        }
        jdbc.update("UPDATE usuario_app SET activo = ? WHERE id = ?", activo, id);
        jdbc.update("DELETE FROM sesion_usuario WHERE usuario_id = ?", id);
        Usuario usuario = cuenta.usuario();
        LOG.info("Estado de usuario actualizado: actorId={}, usuarioId={}, activo={}", actorId, id, activo);
        return new Usuario(id, usuario.usuario(), usuario.nombre(), usuario.rol(), activo);
    }

    @Transactional
    public void cambiarPassword(long actorId, long id, String password) {
        validarPassword(password);
        String passwordHash = passwordEncoder.encode(password);
        bloquearAdministracion();
        requerirUsuario(id);
        jdbc.update("""
                UPDATE usuario_app SET password_hash = ?, intentos_fallidos = 0, bloqueado_hasta = NULL
                WHERE id = ?
                """, passwordHash, id);
        jdbc.update("DELETE FROM sesion_usuario WHERE usuario_id = ?", id);
        LOG.info("Contraseña actualizada y sesiones revocadas: actorId={}, usuarioId={}", actorId, id);
    }

    @Transactional
    public void bootstrapAdmin(String usuario, String password) {
        bloquearAdministracion();
        Long existentes = jdbc.queryForObject("SELECT count(*) FROM usuario_app WHERE rol = 'ADMIN'", Long.class);
        if (existentes != null && existentes > 0) {
            return;
        }
        String normalizado = validarUsuario(usuario);
        validarPassword(password);
        Usuario creado = insertarUsuario(normalizado, "Administrador", passwordEncoder.encode(password), Rol.ADMIN);
        if (creado == null) {
            throw new AuthException(409, "ADMIN_INICIAL_CONFLICTO",
                    "El nombre del administrador inicial ya está asignado a otra cuenta.");
        }
        LOG.info("Administrador inicial creado: usuarioId={}", creado.id());
    }

    private Usuario insertarUsuario(String usuario, String nombre, String hash, Rol rol) {
        List<Usuario> insertados = jdbc.query("""
                INSERT INTO usuario_app (usuario, nombre, password_hash, rol, activo, intentos_fallidos, creado_en)
                VALUES (?, ?, ?, ?, TRUE, 0, ?)
                ON CONFLICT (usuario) DO NOTHING
                RETURNING id, usuario, nombre, rol, activo
                """, AuthService::mapUsuario, usuario, nombre, hash, rol.name(), timestamp(clock.instant()));
        return insertados.isEmpty() ? null : insertados.getFirst();
    }

    private void bloquearAdministracion() {
        // El bloqueo transaccional también coordina múltiples instancias de la API.
        jdbc.query("SELECT pg_advisory_xact_lock(?)", resultSet -> { }, BLOQUEO_ADMIN);
    }

    private long contarAdministradoresActivos() {
        Long cantidad = jdbc.queryForObject("SELECT count(*) FROM usuario_app WHERE rol = 'ADMIN' AND activo", Long.class);
        return cantidad == null ? 0 : cantidad;
    }

    private Credenciales requerirUsuario(long id) {
        if (id <= 0) {
            throw solicitudInvalida("El id del usuario debe ser positivo.");
        }
        Credenciales cuenta = buscarPorIdParaActualizar(id);
        if (cuenta == null) {
            throw new AuthException(404, "USUARIO_NO_ENCONTRADO", "No existe el usuario solicitado.");
        }
        return cuenta;
    }

    private Credenciales buscarPorUsuarioParaActualizar(String usuario) {
        List<Credenciales> cuentas = jdbc.query("SELECT " + COLUMNAS_CREDENCIALES
                + " FROM usuario_app WHERE usuario = ? FOR UPDATE", AuthService::mapCredenciales, usuario);
        return cuentas.isEmpty() ? null : cuentas.getFirst();
    }

    private Credenciales buscarPorIdParaActualizar(long id) {
        List<Credenciales> cuentas = jdbc.query("SELECT " + COLUMNAS_CREDENCIALES
                + " FROM usuario_app WHERE id = ? FOR UPDATE", AuthService::mapCredenciales, id);
        return cuentas.isEmpty() ? null : cuentas.getFirst();
    }

    private Long buscarUsuarioDeToken(String hash) {
        List<Long> ids = jdbc.query("SELECT usuario_id FROM sesion_usuario WHERE token_hash = ?",
                (rs, row) -> rs.getLong("usuario_id"), hash);
        return ids.isEmpty() ? null : ids.getFirst();
    }

    private Sesion buscarSesionParaActualizar(long id) {
        List<Sesion> sesiones = jdbc.query("""
                SELECT token_hash, ultima_actividad, expira_en FROM sesion_usuario
                WHERE usuario_id = ? FOR UPDATE
                """, (rs, row) -> new Sesion(rs.getString("token_hash"),
                rs.getTimestamp("ultima_actividad").toInstant(), rs.getTimestamp("expira_en").toInstant()), id);
        return sesiones.isEmpty() ? null : sesiones.getFirst();
    }

    private void revocarSesion(long id, String hash) {
        jdbc.update("DELETE FROM sesion_usuario WHERE usuario_id = ? AND token_hash = ?", id, hash);
    }

    private static boolean sesionVigente(Sesion sesion, Instant ahora) {
        return sesion.expiraEn().isAfter(ahora)
                && sesion.ultimaActividad().plus(INACTIVIDAD_MAXIMA).isAfter(ahora);
    }

    private String nuevoToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hashTokenValido(String token) {
        return token != null && TOKEN_VALIDO.matcher(token).matches() ? hashToken(token) : null;
    }

    private static String hashToken(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 no está disponible.", exception);
        }
    }

    private static String normalizarUsuario(String usuario) {
        return usuario == null ? "" : usuario.trim().toLowerCase(Locale.ROOT);
    }

    private static String validarUsuario(String usuario) {
        String normalizado = normalizarUsuario(usuario);
        if (!USUARIO_VALIDO.matcher(normalizado).matches()) {
            throw solicitudInvalida("El usuario debe tener de 3 a 60 caracteres: letras, números, punto, guion o guion bajo.");
        }
        return normalizado;
    }

    private static String validarNombre(String nombre) {
        String normalizado = nombre == null ? "" : nombre.trim();
        if (normalizado.isEmpty() || normalizado.length() > 160) {
            throw solicitudInvalida("El nombre debe tener entre 1 y 160 caracteres.");
        }
        return normalizado;
    }

    private static void validarPassword(String password) {
        if (password == null || password.length() < 12 || password.length() > 128 || password.isBlank()) {
            throw solicitudInvalida("La contraseña debe tener entre 12 y 128 caracteres y no puede estar vacía.");
        }
    }

    private static AuthException credencialesInvalidas() {
        return new AuthException(401, "CREDENCIALES_INVALIDAS", "Usuario o contraseña incorrectos.");
    }

    private static AuthException solicitudInvalida(String detalle) {
        return new AuthException(400, "SOLICITUD_INVALIDA", detalle);
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Usuario mapUsuario(ResultSet rs, int row) throws SQLException {
        return new Usuario(rs.getLong("id"), rs.getString("usuario"), rs.getString("nombre"),
                Rol.valueOf(rs.getString("rol")), rs.getBoolean("activo"));
    }

    private static Credenciales mapCredenciales(ResultSet rs, int row) throws SQLException {
        Timestamp bloqueo = rs.getTimestamp("bloqueado_hasta");
        return new Credenciales(mapUsuario(rs, row), rs.getString("password_hash"),
                rs.getInt("intentos_fallidos"), bloqueo == null ? null : bloqueo.toInstant());
    }

    private record Credenciales(Usuario usuario, String passwordHash, int intentosFallidos, Instant bloqueadoHasta) { }

    private record Sesion(String tokenHash, Instant ultimaActividad, Instant expiraEn) { }
}
