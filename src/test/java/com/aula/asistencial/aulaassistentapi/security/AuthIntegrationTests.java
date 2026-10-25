package com.aula.asistencial.aulaassistentapi.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.aula.asistencial.aulaassistentapi.security.AuthDtos.CrearUsuarioRequest;
import com.aula.asistencial.aulaassistentapi.security.AuthDtos.Rol;
import io.github.cdimascio.dotenv.Dotenv;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthIntegrationTests {

    private static final Dotenv DOTENV = Dotenv.configure().ignoreIfMissing().load();
    private static final String SCHEMA = "aula_auth_test_" + UUID.randomUUID().toString().replace("-", "");
    private static final String DATABASE_URL = DOTENV.get(
            "AULA_DB_URL", "jdbc:postgresql://localhost:5432/postgres");
    private static final String DATABASE_USER = DOTENV.get("AULA_DB_USER", "postgres");
    private static final String DATABASE_PASSWORD = requiredDatabasePassword();
    private static final String TEST_URL = DATABASE_URL
            + (DATABASE_URL.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA + ",public";
    private static final String ADMIN = "admin.pruebas";
    private static final String PROFESSIONAL = "profesional.pruebas";
    private static final String PASSWORD = "ClaveSeguraParaPruebas123!";
    private static final String NEW_PASSWORD = "UnaClaveDiferenteYSegura456!";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = client();
    private static boolean schemaCreated;

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AuthService auth;

    @Autowired
    private ApplicationContext context;

    private long professionalId;

    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry properties) throws SQLException {
        try (var connection = DriverManager.getConnection(DATABASE_URL, DATABASE_USER, DATABASE_PASSWORD);
                var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + SCHEMA);
            schemaCreated = true;
        }
        properties.add("spring.datasource.url", () -> TEST_URL);
        properties.add("spring.datasource.username", () -> DATABASE_USER);
        properties.add("spring.datasource.password", () -> DATABASE_PASSWORD);
        properties.add("spring.flyway.default-schema", () -> SCHEMA);
        properties.add("spring.flyway.schemas", () -> SCHEMA);
        properties.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        properties.add("aula.bootstrap.enabled", () -> "false");
    }

    @AfterAll
    static void dropIsolatedSchema() throws SQLException {
        if (schemaCreated) {
            try (var connection = DriverManager.getConnection(DATABASE_URL, DATABASE_USER, DATABASE_PASSWORD);
                    var statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
            }
        }
    }

    @BeforeEach
    void seedIndependentUsers() {
        assertEquals(SCHEMA, jdbc.queryForObject("SELECT current_schema()", String.class),
                "Authentication tests must use their isolated schema before changing any data");
        jdbc.execute("TRUNCATE sesion_usuario, usuario_app, limite_login RESTART IDENTITY CASCADE");
        auth.crearUsuario(new CrearUsuarioRequest(ADMIN, "Administradora de pruebas", PASSWORD, Rol.ADMIN));
        auth.crearUsuario(new CrearUsuarioRequest(
                PROFESSIONAL, "Profesional de pruebas", PASSWORD, Rol.PROFESIONAL));
        professionalId = jdbc.queryForObject(
                "SELECT id FROM usuario_app WHERE usuario = ?", Long.class, PROFESSIONAL);
    }

    @Test
    void applicationDoesNotCreateTheDefaultSpringSecurityUserService() {
        assertTrue(context.getBeansOfType(UserDetailsService.class).isEmpty(),
                "Only persisted Aula accounts may provide application credentials");
    }

    @Test
    void bootstrapCreatesOneAdministratorAndNeverReplacesExistingCredentials() throws Exception {
        jdbc.execute("TRUNCATE sesion_usuario, usuario_app, limite_login RESTART IDENTITY CASCADE");
        auth.bootstrapAdmin("admin.inicial", PASSWORD);
        var original = jdbc.queryForMap("SELECT * FROM usuario_app");

        auth.bootstrapAdmin("admin.alternativo", NEW_PASSWORD);

        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM usuario_app", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM usuario_app WHERE rol = 'ADMIN' AND activo", Integer.class));
        assertEquals(original, jdbc.queryForMap("SELECT * FROM usuario_app"),
                "A later startup must preserve the administrator and the original password hash");
        assertStatus(200, login("admin.inicial", PASSWORD));
        assertProblem(401, "CREDENCIALES_INVALIDAS", login("admin.inicial", NEW_PASSWORD));
        assertProblem(401, "CREDENCIALES_INVALIDAS", login("admin.alternativo", NEW_PASSWORD));
    }

    @Test
    void businessAndAccountEndpointsRequireAuthentication() throws Exception {
        for (var path : List.of("/api/espacios", "/api/docentes", "/api/asignaciones",
                "/api/auth/me", "/api/usuarios")) {
            assertProblem(401, "NO_AUTENTICADO", request("GET", path, null, null));
        }
        assertProblem(401, "NO_AUTENTICADO", request("POST", "/api/auth/logout", null, null));
        assertProblem(401, "NO_AUTENTICADO",
                request("POST", "/api/espacios", Map.of("nombre", "Aula protegida"), null));
    }

    @Test
    void loginReturnsAnOpaqueTokenAndPublicUserWithoutExposingStoredSecrets() throws Exception {
        var login = login(PROFESSIONAL.toUpperCase(java.util.Locale.ROOT), PASSWORD);
        assertStatus(200, login);
        var response = body(login);
        var token = response.path("token").asText();
        assertTrue(token.length() >= 40);
        assertEquals("Bearer", response.path("tipo").asText());
        assertTrue(Instant.parse(response.path("expiraEn").asText()).isAfter(Instant.now()));
        assertEquals(professionalId, response.path("usuario").path("id").asLong());
        assertEquals("PROFESIONAL", response.path("usuario").path("rol").asText());
        assertTrue(response.path("usuario").path("activo").asBoolean());

        var passwordHash = jdbc.queryForObject(
                "SELECT password_hash FROM usuario_app WHERE id = ?", String.class, professionalId);
        var tokenHash = jdbc.queryForObject(
                "SELECT token_hash FROM sesion_usuario WHERE usuario_id = ?", String.class, professionalId);
        assertNotNull(passwordHash);
        assertNotNull(tokenHash);
        assertNotEquals(PASSWORD, passwordHash);
        assertNotEquals(passwordHash, jdbc.queryForObject(
                "SELECT password_hash FROM usuario_app WHERE usuario = ?", String.class, ADMIN),
                "Equal passwords require independent random salts");
        assertNotEquals(token, tokenHash);
        assertTrue(passwordHash.length() >= 60);
        assertTrue(tokenHash.length() >= 64);
        assertNoSecrets(login, tokenHash, passwordHash);

        var me = request("GET", "/api/auth/me", null, token);
        assertStatus(200, me);
        assertNoSecrets(me, tokenHash, passwordHash);
        assertStatus(200, request("GET", "/api/espacios", null, token));
    }

    @Test
    void unknownUserAndWrongPasswordReturnTheSameGenericFailure() throws Exception {
        var wrongPassword = login(PROFESSIONAL, "ContrasenaIncorrecta123!");
        var unknownUser = login("nadie.registrado", PASSWORD);
        assertProblem(401, "CREDENCIALES_INVALIDAS", wrongPassword);
        assertProblem(401, "CREDENCIALES_INVALIDAS", unknownUser);
        assertEquals(body(wrongPassword).path("detail").asText(), body(unknownUser).path("detail").asText());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM sesion_usuario", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT intentos_fallidos FROM usuario_app WHERE id = ?", Integer.class, professionalId));
    }

    @Test
    void invalidTokensAndMalformedAuthorizationCannotReachProtectedEndpoints() throws Exception {
        assertProblem(401, "NO_AUTENTICADO",
                request("GET", "/api/auth/me", null, "token-inventado"));
        assertProblem(401, "NO_AUTENTICADO",
                request("GET", "/api/auth/me", null, "x".repeat(43)));
        var malformed = HttpRequest.newBuilder(uri("/api/auth/me"))
                .header("Authorization", "Basic dXNlcjpwYXNz")
                .GET().build();
        assertProblem(401, "NO_AUTENTICADO", HTTP.send(malformed, HttpResponse.BodyHandlers.ofString()));
    }

    @Test
    void anotherClientCannotOpenASecondSessionAndTheOriginalRemainsUsable() throws Exception {
        var first = login(PROFESSIONAL, PASSWORD);
        assertStatus(200, first);
        assertProblem(401, "CREDENCIALES_INVALIDAS", login(PROFESSIONAL, "ClaveEquivocada123!"));
        var second = request(client(), "POST", "/api/auth/login", credentials(PROFESSIONAL, PASSWORD), null);
        assertProblem(409, "SESION_ACTIVA", second);
        assertStatus(200, request("GET", "/api/auth/me", null, token(first)));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM sesion_usuario", Integer.class));
    }

    @Test
    void concurrentLoginsAtomicallyAcceptExactlyOneSession() throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = List.of(executor.submit(() -> concurrentLogin(ready, start)),
                    executor.submit(() -> concurrentLogin(ready, start)));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            var first = futures.get(0).get(20, TimeUnit.SECONDS);
            var second = futures.get(1).get(20, TimeUnit.SECONDS);
            assertEquals(List.of(200, 409), List.of(first.statusCode(), second.statusCode()).stream().sorted().toList());
            var accepted = first.statusCode() == 200 ? first : second;
            var rejected = first.statusCode() == 409 ? first : second;
            assertProblem(409, "SESION_ACTIVA", rejected);
            assertStatus(200, request("GET", "/api/auth/me", null, token(accepted)));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM sesion_usuario", Integer.class));
        } finally {
            start.countDown();
        }
    }

    @Test
    void logoutRevokesTheOldTokenAndAllowsANewLoginFromAnotherClient() throws Exception {
        var first = login(PROFESSIONAL, PASSWORD);
        assertStatus(200, first);
        assertStatus(204, request("POST", "/api/auth/logout", null, token(first)));
        assertProblem(401, "NO_AUTENTICADO", request("GET", "/api/auth/me", null, token(first)));
        var next = request(client(), "POST", "/api/auth/login", credentials(PROFESSIONAL, PASSWORD), null);
        assertStatus(200, next);
        assertNotEquals(token(first), token(next));
    }

    @ParameterizedTest
    @ValueSource(strings = {"inactividad", "absoluta"})
    void expiredSessionsLoseAccessAndDoNotPreventANewLogin(String reason) throws Exception {
        var first = login(PROFESSIONAL, PASSWORD);
        assertStatus(200, first);
        if (reason.equals("inactividad")) {
            jdbc.update("UPDATE sesion_usuario SET creada_en = CURRENT_TIMESTAMP - INTERVAL '31 minutes', "
                    + "ultima_actividad = CURRENT_TIMESTAMP - INTERVAL '31 minutes' WHERE usuario_id = ?", professionalId);
        } else {
            jdbc.update("UPDATE sesion_usuario SET creada_en = CURRENT_TIMESTAMP - INTERVAL '9 hours', "
                    + "ultima_actividad = CURRENT_TIMESTAMP - INTERVAL '1 minute', "
                    + "expira_en = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE usuario_id = ?", professionalId);
        }
        assertProblem(401, "NO_AUTENTICADO", request("GET", "/api/auth/me", null, token(first)));
        var next = login(PROFESSIONAL, PASSWORD);
        assertStatus(200, next);
        assertNotEquals(token(first), token(next));
    }

    @Test
    void persistedSessionsAuthenticateThroughANewServiceAndDatabaseRevocationTakesEffect() throws Exception {
        var login = login(PROFESSIONAL, PASSWORD);
        assertStatus(200, login);
        var freshService = context.getAutowireCapableBeanFactory().createBean(AuthService.class);
        assertNotSame(auth, freshService);
        assertNotNull(freshService.authenticate(token(login)));
        jdbc.update("DELETE FROM sesion_usuario WHERE usuario_id = ?", professionalId);
        assertProblem(401, "NO_AUTENTICADO", request("GET", "/api/auth/me", null, token(login)));
    }

    @Test
    void protectedRequestsRefreshActivityWithoutExtendingAbsoluteExpiration() throws Exception {
        var login = login(PROFESSIONAL, PASSWORD);
        assertStatus(200, login);
        jdbc.update("UPDATE sesion_usuario SET creada_en = CURRENT_TIMESTAMP - INTERVAL '2 minutes', "
                + "ultima_actividad = CURRENT_TIMESTAMP - INTERVAL '1 minute' WHERE usuario_id = ?", professionalId);
        var absoluteExpiration = jdbc.queryForObject(
                "SELECT expira_en FROM sesion_usuario WHERE usuario_id = ?", java.sql.Timestamp.class, professionalId);
        assertStatus(200, request("GET", "/api/auth/me", null, token(login)));
        assertTrue(jdbc.queryForObject("SELECT ultima_actividad > CURRENT_TIMESTAMP - INTERVAL '10 seconds' "
                + "FROM sesion_usuario WHERE usuario_id = ?", Boolean.class, professionalId));
        assertEquals(absoluteExpiration, jdbc.queryForObject(
                "SELECT expira_en FROM sesion_usuario WHERE usuario_id = ?", java.sql.Timestamp.class, professionalId));
    }

    @Test
    void loginReplacesAnExpiredSessionWithoutRequiringUseOfTheExpiredToken() throws Exception {
        var first = login(PROFESSIONAL, PASSWORD);
        assertStatus(200, first);
        jdbc.update("UPDATE sesion_usuario SET creada_en = CURRENT_TIMESTAMP - INTERVAL '31 minutes', "
                + "ultima_actividad = CURRENT_TIMESTAMP - INTERVAL '31 minutes' WHERE usuario_id = ?", professionalId);
        var next = login(PROFESSIONAL, PASSWORD);
        assertStatus(200, next);
        assertNotEquals(token(first), token(next));
        assertProblem(401, "NO_AUTENTICADO", request("GET", "/api/auth/me", null, token(first)));
        assertStatus(200, request("GET", "/api/auth/me", null, token(next)));
    }

    @Test
    void failedAttemptsPersistAndLockEvenTheCorrectPasswordUntilTheLockExpires() throws Exception {
        for (int attempt = 1; attempt <= 5; attempt++) {
            assertProblem(401, "CREDENCIALES_INVALIDAS", login(PROFESSIONAL, "ClaveEquivocada123!"));
            assertEquals(attempt, jdbc.queryForObject(
                    "SELECT intentos_fallidos FROM usuario_app WHERE id = ?", Integer.class, professionalId));
        }
        assertTrue(jdbc.queryForObject(
                "SELECT bloqueado_hasta > CURRENT_TIMESTAMP FROM usuario_app WHERE id = ?", Boolean.class, professionalId));
        assertProblem(401, "CREDENCIALES_INVALIDAS", login(PROFESSIONAL, PASSWORD));
        jdbc.update("UPDATE usuario_app SET bloqueado_hasta = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE id = ?",
                professionalId);
        assertStatus(200, login(PROFESSIONAL, PASSWORD));
        assertEquals(0, jdbc.queryForObject(
                "SELECT intentos_fallidos FROM usuario_app WHERE id = ?", Integer.class, professionalId));
    }

    @Test
    void loginAttemptsAreAlsoLimitedForUnknownUsersRegardlessOfForwardedHeaders() throws Exception {
        for (int attempt = 1; attempt <= 30; attempt++) {
            var request = HttpRequest.newBuilder(uri("/api/auth/login"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("X-Forwarded-For", "192.0.2." + attempt)
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(
                            credentials("inexistente." + attempt, PASSWORD)))).build();
            assertProblem(401, "CREDENCIALES_INVALIDAS", HTTP.send(request, HttpResponse.BodyHandlers.ofString()));
        }
        assertProblem(429, "DEMASIADOS_INTENTOS", login("otro.inexistente", PASSWORD));
    }

    @Test
    void professionalsCannotListCreateDisableOrResetAccounts() throws Exception {
        var login = login(PROFESSIONAL, PASSWORD);
        assertStatus(200, login);
        var token = token(login);
        assertProblem(403, "ACCESO_DENEGADO", request("GET", "/api/usuarios", null, token));
        assertProblem(403, "ACCESO_DENEGADO", request("POST", "/api/usuarios",
                newUser("nuevo.profesional", PASSWORD), token));
        assertProblem(403, "ACCESO_DENEGADO", request("PATCH", "/api/usuarios/" + professionalId + "/estado",
                Map.of("activo", false), token));
        assertProblem(403, "ACCESO_DENEGADO", request("PUT", "/api/usuarios/" + professionalId + "/password",
                Map.of("password", NEW_PASSWORD), token));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM usuario_app", Integer.class));
    }

    @Test
    void administratorsCannotDisableThemselvesAndKeepTheirActiveSession() throws Exception {
        var admin = login(ADMIN, PASSWORD);
        assertStatus(200, admin);
        var adminId = body(admin).path("usuario").path("id").asLong();
        assertProblem(403, "AUTO_DESACTIVACION", request("PATCH", "/api/usuarios/" + adminId + "/estado",
                Map.of("activo", false), token(admin)));
        assertTrue(jdbc.queryForObject("SELECT activo FROM usuario_app WHERE id = ?", Boolean.class, adminId));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM sesion_usuario WHERE usuario_id = ?", Integer.class, adminId));
        assertStatus(200, request("GET", "/api/auth/me", null, token(admin)));
        assertStatus(200, request("GET", "/api/usuarios", null, token(admin)));
    }

    @Test
    void administratorsCreateAndListAccountsWithoutExposingPasswordsAndRejectDuplicates() throws Exception {
        var admin = login(ADMIN, PASSWORD);
        assertStatus(200, admin);
        var created = request("POST", "/api/usuarios", newUser("nueva.profesional", PASSWORD), token(admin));
        assertStatus(201, created);
        assertEquals("nueva.profesional", body(created).path("usuario").asText());
        assertEquals("PROFESIONAL", body(created).path("rol").asText());
        var listed = request("GET", "/api/usuarios", null, token(admin));
        assertStatus(200, listed);
        assertTrue(body(listed).isArray());
        assertEquals(3, body(listed).size());
        assertFalse(listed.body().contains(PASSWORD));
        assertFalse(listed.body().contains("password"));
        assertFalse(listed.body().contains("hash"));
        assertProblem(409, "USUARIO_EXISTENTE",
                request("POST", "/api/usuarios", newUser("NUEVA.PROFESIONAL", PASSWORD), token(admin)));
    }

    @Test
    void accountCreationRejectsWeakPasswordLengthsAndUnsafeUsernames() throws Exception {
        var admin = login(ADMIN, PASSWORD);
        assertStatus(200, admin);
        for (var password : List.of("Corta123!", "x".repeat(129))) {
            assertStatus(400, request("POST", "/api/usuarios", newUser("cuenta.validada", password), token(admin)));
        }
        for (var username : List.of("ab", "usuario con espacios", "x".repeat(61), "usuario' OR '1'='1")) {
            assertStatus(400, request("POST", "/api/usuarios", newUser(username, PASSWORD), token(admin)));
        }
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM usuario_app", Integer.class));
    }

    @Test
    void administratorPasswordResetImmediatelyRevokesTheSessionAndOldPassword() throws Exception {
        var professional = login(PROFESSIONAL, PASSWORD);
        var admin = login(ADMIN, PASSWORD);
        assertStatus(200, professional);
        assertStatus(200, admin);
        assertStatus(204, request("PUT", "/api/usuarios/" + professionalId + "/password",
                Map.of("password", NEW_PASSWORD), token(admin)));
        assertProblem(401, "NO_AUTENTICADO", request("GET", "/api/auth/me", null, token(professional)));
        assertProblem(401, "CREDENCIALES_INVALIDAS", login(PROFESSIONAL, PASSWORD));
        assertStatus(200, login(PROFESSIONAL, NEW_PASSWORD));
    }

    @Test
    void disablingAnAccountRevokesItsSessionAndReactivationRequiresANewLogin() throws Exception {
        var professional = login(PROFESSIONAL, PASSWORD);
        var admin = login(ADMIN, PASSWORD);
        assertStatus(200, professional);
        assertStatus(200, admin);
        var path = "/api/usuarios/" + professionalId + "/estado";
        assertStatus(200, request("PATCH", path, Map.of("activo", false), token(admin)));
        assertProblem(401, "NO_AUTENTICADO", request("GET", "/api/auth/me", null, token(professional)));
        assertProblem(401, "CREDENCIALES_INVALIDAS", login(PROFESSIONAL, PASSWORD));
        assertStatus(200, request("PATCH", path, Map.of("activo", true), token(admin)));
        assertStatus(200, login(PROFESSIONAL, PASSWORD));
    }

    private HttpResponse<String> concurrentLogin(CountDownLatch ready, CountDownLatch start) throws Exception {
        var client = client();
        ready.countDown();
        assertTrue(start.await(5, TimeUnit.SECONDS));
        return request(client, "POST", "/api/auth/login", credentials(PROFESSIONAL, PASSWORD), null);
    }

    private HttpResponse<String> login(String user, String password) throws Exception {
        return request("POST", "/api/auth/login", credentials(user, password), null);
    }

    private HttpResponse<String> request(String method, String path, Object payload, String token) throws Exception {
        return request(HTTP, method, path, payload, token);
    }

    private HttpResponse<String> request(HttpClient client, String method, String path, Object payload, String token)
            throws Exception {
        var builder = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json, application/problem+json");
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (payload == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(payload)));
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static Map<String, Object> credentials(String user, String password) {
        return Map.of("usuario", user, "password", password);
    }

    private static Map<String, Object> newUser(String username, String password) {
        return Map.of("usuario", username, "nombre", "Profesional nueva", "password", password,
                "rol", "PROFESIONAL");
    }

    private static HttpClient client() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    private static JsonNode body(HttpResponse<String> response) {
        return JSON.readTree(response.body());
    }

    private static String token(HttpResponse<String> response) {
        return body(response).path("token").asText();
    }

    private static void assertStatus(int expected, HttpResponse<String> response) {
        assertEquals(expected, response.statusCode(), response::body);
    }

    private static void assertProblem(int expectedStatus, String expectedCode, HttpResponse<String> response) {
        assertStatus(expectedStatus, response);
        assertTrue(response.headers().firstValue("Content-Type").orElse("")
                .startsWith("application/problem+json"), response::body);
        assertEquals(expectedCode, body(response).path("codigo").asText());
        assertEquals(expectedStatus, body(response).path("status").asInt());
        assertFalse(body(response).path("detail").asText().isBlank(), response::body);
        assertFalse(response.body().contains(PASSWORD));
    }

    private static void assertNoSecrets(HttpResponse<String> response, String tokenHash, String passwordHash) {
        assertFalse(response.body().contains(PASSWORD));
        assertFalse(response.body().contains(tokenHash));
        assertFalse(response.body().contains(passwordHash));
        assertFalse(response.body().toLowerCase(java.util.Locale.ROOT).contains("password"));
        assertFalse(response.body().toLowerCase(java.util.Locale.ROOT).contains("hash"));
    }

    private static String requiredDatabasePassword() {
        var password = DOTENV.get("AULA_DB_PASSWORD");
        if (password == null || password.isBlank()) {
            throw new IllegalStateException(
                    "Define AULA_DB_PASSWORD in .env or the environment to run PostgreSQL authentication tests");
        }
        return password;
    }
}
