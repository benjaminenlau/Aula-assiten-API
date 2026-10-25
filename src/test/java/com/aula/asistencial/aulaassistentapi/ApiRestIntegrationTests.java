package com.aula.asistencial.aulaassistentapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Stream;

import com.aula.asistencial.aulaassistentapi.security.AuthDtos;
import com.aula.asistencial.aulaassistentapi.security.AuthService;
import io.github.cdimascio.dotenv.Dotenv;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiRestIntegrationTests {

    private static final Dotenv DOTENV = Dotenv.configure().ignoreIfMissing().load();
    private static final String SCHEMA = "aula_api_test_" + UUID.randomUUID().toString().replace("-", "");
    private static final String DATABASE_URL = DOTENV.get(
            "AULA_DB_URL", "jdbc:postgresql://localhost:5432/postgres");
    private static final String USER = DOTENV.get("AULA_DB_USER", "postgres");
    private static final String PASSWORD = requiredPassword();
    private static final String TEST_URL = DATABASE_URL
            + (DATABASE_URL.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA + ",public";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    private static boolean schemaCreated;

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AuthService auth;

    private String token;

    private long espacio1;
    private long espacio2;
    private long docente1;
    private long docente2;

    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry properties) throws SQLException {
        try (var connection = DriverManager.getConnection(DATABASE_URL, USER, PASSWORD);
                var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + SCHEMA);
            schemaCreated = true;
        }
        properties.add("spring.datasource.url", () -> TEST_URL);
        properties.add("spring.datasource.username", () -> USER);
        properties.add("spring.datasource.password", () -> PASSWORD);
        properties.add("spring.flyway.default-schema", () -> SCHEMA);
        properties.add("spring.flyway.schemas", () -> SCHEMA);
        properties.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        properties.add("aula.bootstrap.enabled", () -> "false");
    }

    @AfterAll
    static void dropIsolatedSchema() throws SQLException {
        if (schemaCreated) {
            try (var connection = DriverManager.getConnection(DATABASE_URL, USER, PASSWORD);
                    var statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
            }
        }
    }

    @BeforeEach
    void prepareIndependentResources() {
        assertEquals(SCHEMA, jdbc.queryForObject("SELECT current_schema()", String.class),
                "The HTTP tests must use their isolated schema before changing any data");
        jdbc.execute("TRUNCATE asignacion, espacio, docente, sesion_usuario, usuario_app RESTART IDENTITY CASCADE");
        auth.crearUsuario(new AuthDtos.CrearUsuarioRequest(
                "profesional.api", "Profesional de pruebas", "ClaveSeguraDePrueba123!", AuthDtos.Rol.PROFESIONAL));
        token = auth.login("profesional.api", "ClaveSeguraDePrueba123!").token();
        espacio1 = insertResource("espacio", "Aula 101");
        espacio2 = insertResource("espacio", "Aula 102");
        docente1 = insertResource("docente", "Docente uno");
        docente2 = insertResource("docente", "Docente dos");
    }

    @ParameterizedTest
    @ValueSource(strings = {"espacios", "docentes"})
    void resourcesSupportCompleteCrud(String resource) throws Exception {
        var created = request("POST", "/api/" + resource, named("Nuevo recurso"));
        assertStatus(201, created);
        var id = body(created).get("id").asLong();
        var path = "/api/" + resource + "/" + id;
        assertLocation(created, path);
        assertEquals("Nuevo recurso", body(created).get("nombre").asText());

        var loaded = request("GET", path, null);
        assertStatus(200, loaded);
        assertEquals(id, body(loaded).get("id").asLong());
        assertEquals("Nuevo recurso", body(loaded).get("nombre").asText());
        assertTrue(ids(request("GET", "/api/" + resource, null)).contains(id));

        var updated = request("PUT", path, named("Nombre actualizado"));
        assertStatus(200, updated);
        assertEquals(id, body(updated).get("id").asLong());
        assertEquals("Nombre actualizado", body(updated).get("nombre").asText());

        var deleted = request("DELETE", path, null);
        assertStatus(204, deleted);
        assertTrue(deleted.body().isEmpty());
        assertProblem(404, request("GET", path, null));
        assertFalse(ids(request("GET", "/api/" + resource, null)).contains(id));
    }

    @Test
    void assignmentsSupportCompleteCrudAndPreserveMicroseconds() throws Exception {
        var inicio = "2026-10-26T08:00:00.123456";
        var fin = "2026-10-26T10:00:00.123456";
        var created = request("POST", "/api/asignaciones", assignment(espacio1, docente1, inicio, fin));
        assertStatus(201, created);
        var id = body(created).get("id").asLong();
        var path = "/api/asignaciones/" + id;
        assertLocation(created, path);
        assertAssignment(body(created), id, espacio1, docente1, inicio, fin);

        var loaded = request("GET", path, null);
        assertStatus(200, loaded);
        assertAssignment(body(loaded), id, espacio1, docente1, inicio, fin);
        assertEquals(Set.of(id), ids(request("GET", "/api/asignaciones", null)));

        var updated = request("PUT", path,
                assignment(espacio2, docente2, at("11:00"), at("13:00")));
        assertStatus(200, updated);
        assertAssignment(body(updated), id, espacio2, docente2, at("11:00"), at("13:00"));

        assertStatus(204, request("DELETE", path, null));
        assertProblem(404, request("GET", path, null));
        assertTrue(ids(request("GET", "/api/asignaciones", null)).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"espacios", "docentes"})
    void resourceNamesRequireNonBlankTextAndAtMost255Characters(String resource) throws Exception {
        for (var invalid : new String[]{null, "", "  ", "n".repeat(256)}) {
            assertProblem(400, request("POST", "/api/" + resource, named(invalid)));
            assertProblem(400, request("PUT", "/api/" + resource + "/1", named(invalid)));
        }
        assertStatus(201, request("POST", "/api/" + resource, named("n".repeat(255))));
        assertEquals(3, ids(request("GET", "/api/" + resource, null)).size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"espacios", "docentes", "asignaciones"})
    void routeIdsMustBePositiveNumbers(String resource) throws Exception {
        assertProblem(400, request("GET", "/api/" + resource + "/0", null));
        assertProblem(400, request("DELETE", "/api/" + resource + "/-1", null));
        assertProblem(400, request("GET", "/api/" + resource + "/abc", null));
    }

    @Test
    void malformedJsonAndDatesReturnBadRequest() throws Exception {
        assertProblem(400, request("POST", "/api/espacios", "{\"nombre\":"));
        assertProblem(400, request("POST", "/api/asignaciones",
                assignment(espacio1, docente1, "fecha-invalida", at("10:00"))));
        assertProblem(400, request("POST", "/api/asignaciones",
                assignment(espacio1, docente1, "2026-02-30T08:00:00", at("10:00"))));
    }

    @ParameterizedTest
    @MethodSource("invalidIntervals")
    void intervalsRequireBothEndpointsAndIncreasingTimes(String inicio, String fin) throws Exception {
        assertProblem(400, request("POST", "/api/asignaciones",
                assignment(espacio1, docente1, inicio, fin)));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM asignacion", Integer.class));
    }

    static Stream<Arguments> invalidIntervals() {
        return Stream.of(
                Arguments.of(at("08:00"), at("08:00")),
                Arguments.of(at("10:00"), at("08:00")),
                Arguments.of(null, at("10:00")),
                Arguments.of(at("08:00"), null));
    }

    @Test
    void timestampsMustFitDatabaseMicrosecondPrecision() throws Exception {
        assertProblem(400, request("POST", "/api/asignaciones",
                assignment(espacio1, docente1, "2026-10-26T08:00:00.123456789", at("10:00"))));
        assertProblem(400, request("POST", "/api/asignaciones",
                assignment(espacio1, docente1, at("08:00"), "2026-10-26T10:00:00.123456789")));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM asignacion", Integer.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"espacio", "docente"})
    void nineToElevenOverEightToTenReportsTheNamedConflict(String resource) throws Exception {
        createAssignment(espacio1, docente1, "08:00", "10:00");
        var response = request("POST", "/api/asignaciones", assignment(
                resource.equals("espacio") ? espacio1 : espacio2,
                resource.equals("docente") ? docente1 : docente2, at("09:00"), at("11:00")));
        assertConflict(resource, response);
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM asignacion", Integer.class));
    }

    @Test
    void touchingHalfOpenIntervalsAreAllowedOnBothEnds() throws Exception {
        var first = createAssignment(espacio1, docente1, "08:00", "10:00");
        var before = createAssignment(espacio1, docente1, "06:00", "08:00");
        var after = createAssignment(espacio1, docente1, "10:00", "12:00");
        assertEquals(Set.of(first, before, after), ids(request("GET", "/api/asignaciones", null)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"espacio", "docente"})
    void conflictingUpdateDoesNotChangeTheOriginalAssignment(String resource) throws Exception {
        createAssignment(espacio1, docente1, "08:00", "10:00");
        var id = createAssignment(espacio2, docente2, "09:00", "11:00");
        var response = request("PUT", "/api/asignaciones/" + id, assignment(
                resource.equals("espacio") ? espacio1 : espacio2,
                resource.equals("docente") ? docente1 : docente2, at("09:00"), at("11:00")));
        assertConflict(resource, response);

        var original = request("GET", "/api/asignaciones/" + id, null);
        assertStatus(200, original);
        assertAssignment(body(original), id, espacio2, docente2, at("09:00"), at("11:00"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"espacio", "docente"})
    void missingAssignmentReferencesReturnNotFound(String resource) throws Exception {
        var payload = assignment(resource.equals("espacio") ? 999999L : espacio1,
                resource.equals("docente") ? 999999L : docente1, at("08:00"), at("10:00"));
        assertProblem(404, request("POST", "/api/asignaciones", payload));
        var id = createAssignment(espacio1, docente1, "08:00", "10:00");
        assertProblem(404, request("PUT", "/api/asignaciones/" + id, payload));
        assertAssignment(body(request("GET", "/api/asignaciones/" + id, null)),
                id, espacio1, docente1, at("08:00"), at("10:00"));
    }

    @Test
    void referenceIdsMustBePresentAndPositive() throws Exception {
        for (var id : new Long[]{null, 0L, -1L}) {
            assertProblem(400, request("POST", "/api/asignaciones",
                    assignment(id, docente1, at("08:00"), at("10:00"))));
            assertProblem(400, request("POST", "/api/asignaciones",
                    assignment(espacio1, id, at("08:00"), at("10:00"))));
        }
    }

    @Test
    void assignmentFiltersUseOverlapAndHalfOpenBoundaries() throws Exception {
        var early = createAssignment(espacio1, docente1, "08:00", "10:00");
        var late = createAssignment(espacio1, docente1, "10:00", "12:00");
        var other = createAssignment(espacio2, docente2, "09:00", "11:00");

        assertEquals(Set.of(early, late), ids(request("GET", "/api/asignaciones?espacioId=" + espacio1, null)));
        assertEquals(Set.of(other), ids(request("GET", "/api/asignaciones?docenteId=" + docente2, null)));
        assertEquals(Set.of(), ids(request("GET", "/api/asignaciones?espacioId=" + espacio1
                + "&docenteId=" + docente2, null)));
        assertEquals(Set.of(late, other), ids(request("GET", filteredTimes("10:00", "11:00"), null)));
        assertEquals(Set.of(early, other), ids(request("GET", filteredTimes("08:00", "10:00"), null)));
        assertEquals(Set.of(), ids(request("GET", filteredTimes("07:00", "08:00"), null)));
        assertEquals(Set.of(late), ids(request("GET", filteredTimes("10:00", "11:00")
                + "&espacioId=" + espacio1 + "&docenteId=" + docente1, null)));
    }

    @Test
    void assignmentFiltersRejectIncompleteOrInvalidRangesAndIds() throws Exception {
        assertProblem(400, request("GET", "/api/asignaciones?desde=" + at("08:00"), null));
        assertProblem(400, request("GET", "/api/asignaciones?hasta=" + at("10:00"), null));
        assertProblem(400, request("GET", filteredTimes("10:00", "08:00"), null));
        assertProblem(400, request("GET", filteredTimes("08:00", "08:00"), null));
        assertProblem(400, request("GET", "/api/asignaciones?desde=fecha-invalida&hasta=" + at("10:00"), null));
        assertProblem(400, request("GET", "/api/asignaciones?espacioId=0", null));
        assertProblem(400, request("GET", "/api/asignaciones?docenteId=-1", null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"espacios", "docentes"})
    void referencedResourcesCannotBeDeletedUntilTheirAssignmentsAreRemoved(String resource) throws Exception {
        var id = createAssignment(espacio1, docente1, "08:00", "10:00");
        var resourceId = resource.equals("espacios") ? espacio1 : docente1;
        var path = "/api/" + resource + "/" + resourceId;
        var failure = request("DELETE", path, null);
        assertProblem(409, failure);
        assertEquals("RECURSO_EN_USO", body(failure).get("codigo").asText());
        assertStatus(200, request("GET", path, null));
        assertStatus(200, request("GET", "/api/asignaciones/" + id, null));

        assertStatus(204, request("DELETE", "/api/asignaciones/" + id, null));
        assertStatus(204, request("DELETE", path, null));
        assertProblem(404, request("GET", path, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"espacios", "docentes", "asignaciones"})
    void absentEntitiesReturnNotFoundForReadUpdateAndDelete(String resource) throws Exception {
        var path = "/api/" + resource + "/999999";
        var payload = resource.equals("asignaciones")
                ? assignment(espacio1, docente1, at("08:00"), at("10:00"))
                : named("Nombre");
        assertProblem(404, request("GET", path, null));
        assertProblem(404, request("PUT", path, payload));
        assertProblem(404, request("DELETE", path, null));
    }

    private HttpResponse<String> request(String method, String path, String payload) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json, application/problem+json");
        if (payload == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(payload));
        }
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private long createAssignment(long espacio, long docente, String inicio, String fin) throws Exception {
        var response = request("POST", "/api/asignaciones", assignment(espacio, docente, at(inicio), at(fin)));
        assertStatus(201, response);
        return body(response).get("id").asLong();
    }

    private long insertResource(String table, String name) {
        return jdbc.queryForObject("INSERT INTO " + table + " (nombre) VALUES (?) RETURNING id", Long.class, name);
    }

    private static String named(String nombre) {
        var value = new LinkedHashMap<String, Object>();
        value.put("nombre", nombre);
        return JSON.writeValueAsString(value);
    }

    private static String assignment(Long espacio, Long docente, String inicio, String fin) {
        var value = new LinkedHashMap<String, Object>();
        value.put("espacioId", espacio);
        value.put("docenteId", docente);
        value.put("inicio", inicio);
        value.put("fin", fin);
        return JSON.writeValueAsString(value);
    }

    private static JsonNode body(HttpResponse<String> response) {
        return JSON.readTree(response.body());
    }

    private static Set<Long> ids(HttpResponse<String> response) {
        assertStatus(200, response);
        var array = body(response);
        assertTrue(array.isArray(), response::body);
        var ids = new TreeSet<Long>();
        array.forEach(value -> ids.add(value.get("id").asLong()));
        return ids;
    }

    private static void assertStatus(int expected, HttpResponse<String> response) {
        assertEquals(expected, response.statusCode(), response::body);
    }

    private static void assertLocation(HttpResponse<String> response, String expectedPath) {
        var location = response.headers().firstValue("Location").orElseThrow();
        assertEquals(expectedPath, URI.create(location).getPath());
    }

    private static void assertProblem(int status, HttpResponse<String> response) {
        assertStatus(status, response);
        assertTrue(response.headers().firstValue("Content-Type").orElse("")
                .startsWith("application/problem+json"), response::body);
        var problem = body(response);
        assertEquals(status, problem.get("status").asInt());
        assertFalse(problem.path("title").asText("").isBlank(), response::body);
        assertFalse(problem.path("detail").asText("").isBlank(), response::body);
    }

    private static void assertConflict(String resource, HttpResponse<String> response) {
        assertProblem(409, response);
        assertEquals("CRUCE_" + resource.toUpperCase(java.util.Locale.ROOT), body(response).get("codigo").asText());
        assertEquals("no_cruce_" + resource, body(response).get("restriccion").asText());
    }

    private static void assertAssignment(JsonNode value, long id, long espacio, long docente,
            String inicio, String fin) {
        assertEquals(id, value.get("id").asLong());
        assertEquals(espacio, value.get("espacioId").asLong());
        assertEquals(docente, value.get("docenteId").asLong());
        assertEquals(LocalDateTime.parse(inicio), LocalDateTime.parse(value.get("inicio").asText()));
        assertEquals(LocalDateTime.parse(fin), LocalDateTime.parse(value.get("fin").asText()));
    }

    private static String at(String time) {
        return "2026-10-26T" + time;
    }

    private static String filteredTimes(String desde, String hasta) {
        return "/api/asignaciones?desde=" + at(desde) + "&hasta=" + at(hasta);
    }

    private static String requiredPassword() {
        var password = DOTENV.get("AULA_DB_PASSWORD");
        if (password == null || password.isBlank()) {
            throw new IllegalStateException(
                    "Define AULA_DB_PASSWORD in .env or the environment to run the PostgreSQL HTTP integration tests");
        }
        return password;
    }
}
