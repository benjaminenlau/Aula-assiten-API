package com.aula.asistencial.aulaassistentapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.aula.asistencial.aulaassistentapi.entity.Asignacion;
import com.aula.asistencial.aulaassistentapi.entity.Docente;
import com.aula.asistencial.aulaassistentapi.entity.Espacio;
import com.aula.asistencial.aulaassistentapi.repository.AsignacionRepository;
import com.aula.asistencial.aulaassistentapi.repository.DocenteRepository;
import com.aula.asistencial.aulaassistentapi.repository.EspacioRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class AulaAssistentApiApplicationTests {

    private static final String SCHEMA = "aula_test_" + UUID.randomUUID().toString().replace("-", "");
    private static final String DATABASE_URL = System.getenv().getOrDefault(
            "AULA_DB_URL", "jdbc:postgresql://localhost:5432/postgres");
    private static final String USER = System.getenv().getOrDefault("AULA_DB_USER", "postgres");
    private static final String PASSWORD = requiredPassword();
    private static final String TEST_URL = DATABASE_URL
            + (DATABASE_URL.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA + ",public";
    private static boolean schemaCreated;

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private EspacioRepository espacios;
    @Autowired
    private DocenteRepository docentes;
    @Autowired
    private AsignacionRepository asignaciones;

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
                "The test must use its isolated schema before changing any data");
        jdbc.execute("TRUNCATE asignacion, espacio, docente RESTART IDENTITY CASCADE");
        espacio1 = insertResource("espacio", "Aula 101");
        espacio2 = insertResource("espacio", "Aula 102");
        docente1 = insertResource("docente", "Docente uno");
        docente2 = insertResource("docente", "Docente dos");
    }

    @Test
    void flywayCreatesVersionOneAndBothNamedExclusions() {
        assertEquals(SCHEMA, jdbc.queryForObject("SELECT current_schema()", String.class));
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM flyway_schema_history
                WHERE version = '1' AND script = 'V1__esquema_inicial.sql' AND success
                """, Integer.class));
        assertTrue(Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'btree_gist')", Boolean.class)));
        assertEquals(2, jdbc.queryForObject("""
                SELECT count(*) FROM pg_constraint
                WHERE conrelid = 'asignacion'::regclass AND contype = 'x'
                  AND conname IN ('no_cruce_espacio', 'no_cruce_docente')
                """, Integer.class));
    }

    @Test
    void jpaPersistsEntitiesAndPreservesTimestampPrecision() {
        var espacio = espacios.saveAndFlush(new Espacio("Laboratorio"));
        var docente = docentes.saveAndFlush(new Docente("Docente JPA"));
        var inicio = LocalDateTime.of(2026, 10, 26, 8, 0, 0, 123456000);
        var fin = inicio.plusHours(2);
        var asignacion = asignaciones.saveAndFlush(new Asignacion(espacio, docente, inicio, fin));

        assertNotNull(asignacion.getId());
        var loaded = asignaciones.findById(asignacion.getId()).orElseThrow();
        assertEquals(espacio.getId(), loaded.getEspacio().getId());
        assertEquals(docente.getId(), loaded.getDocente().getId());
        assertEquals(inicio, loaded.getInicio());
        assertEquals(fin, loaded.getFin());
    }

    @Test
    void nineToElevenOverEightToTenRejectsSameSpaceWith23P01() {
        insertAssignment(espacio1, docente1, "08:00", "10:00");
        assertSqlFailure("23P01", "no_cruce_espacio",
                () -> insertAssignment(espacio1, docente2, "09:00", "11:00"));
    }

    @Test
    void nineToElevenOverEightToTenRejectsSameTeacherWith23P01() {
        insertAssignment(espacio1, docente1, "08:00", "10:00");
        assertSqlFailure("23P01", "no_cruce_docente",
                () -> insertAssignment(espacio2, docente1, "09:00", "11:00"));
    }

    @Test
    void halfOpenIntervalsAllowTouchingOnEitherEnd() {
        insertAssignment(espacio1, docente1, "08:00", "10:00");
        insertAssignment(espacio1, docente1, "06:00", "08:00");
        insertAssignment(espacio1, docente1, "10:00", "12:00");
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM asignacion", Integer.class));
    }

    @Test
    void differentSpaceAndTeacherAllowSameInterval() {
        insertAssignment(espacio1, docente1, "08:00", "10:00");
        insertAssignment(espacio2, docente2, "08:00", "10:00");
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM asignacion", Integer.class));
    }

    @Test
    void duplicateIntervalIsRejectedForEachResourceIndependently() {
        insertAssignment(espacio1, docente1, "08:00", "10:00");
        assertSqlFailure("23P01", "no_cruce_espacio",
                () -> insertAssignment(espacio1, docente2, "08:00", "10:00"));
        assertSqlFailure("23P01", "no_cruce_docente",
                () -> insertAssignment(espacio2, docente1, "08:00", "10:00"));
    }

    @Test
    void emptyAndReversedIntervalsViolateTheCheck() {
        assertSqlFailure("23514", "intervalo_valido",
                () -> insertAssignment(espacio1, docente1, "08:00", "08:00"));
        assertSqlFailure("23514", "intervalo_valido",
                () -> insertAssignment(espacio1, docente1, "10:00", "08:00"));
    }

    @Test
    void updateCannotIntroduceSpaceOrTeacherOverlap() {
        insertAssignment(espacio1, docente1, "08:00", "10:00");
        long id = insertAssignment(espacio2, docente2, "09:00", "11:00");
        assertSqlFailure("23P01", "no_cruce_espacio",
                () -> jdbc.update("UPDATE asignacion SET espacio_id = ? WHERE id = ?", espacio1, id));
        assertSqlFailure("23P01", "no_cruce_docente",
                () -> jdbc.update("UPDATE asignacion SET docente_id = ? WHERE id = ?", docente1, id));
    }

    @Test
    void assignmentsRequireExistingResourcesAndNonNullTimes() {
        assertSqlFailure("23503", null,
                () -> insertAssignment(-1, docente1, "08:00", "10:00"));
        assertSqlFailure("23503", null,
                () -> insertAssignment(espacio1, -1, "08:00", "10:00"));
        assertSqlFailure("23502", null, () -> jdbc.update("""
                INSERT INTO asignacion (espacio_id, docente_id, inicio, fin) VALUES (?, ?, NULL, ?)
                """, espacio1, docente1, timestamp("10:00")));
        assertSqlFailure("23502", null, () -> jdbc.update("""
                INSERT INTO asignacion (espacio_id, docente_id, inicio, fin) VALUES (?, ?, ?, NULL)
                """, espacio1, docente1, timestamp("08:00")));
    }

    @Test
    void concurrentOverlappingTransactionsCannotBothCommit() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try (var first = DriverManager.getConnection(TEST_URL, USER, PASSWORD);
                var second = DriverManager.getConnection(TEST_URL, USER, PASSWORD)) {
            first.setAutoCommit(false);
            second.setAutoCommit(false);
            try {
                insertAssignment(first, espacio1, docente1, "08:00", "10:00");
                var started = new CountDownLatch(1);
                var competingInsert = executor.submit(() -> {
                    try (var statement = second.createStatement()) {
                        statement.execute("SET LOCAL lock_timeout = '5s'");
                        started.countDown();
                        insertAssignment(second, espacio1, docente2, "09:00", "11:00");
                        second.commit();
                        return (SQLException) null;
                    } catch (SQLException failure) {
                        second.rollback();
                        return failure;
                    }
                });
                assertTrue(started.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> competingInsert.get(200, TimeUnit.MILLISECONDS));
                first.commit();
                var failure = competingInsert.get(5, TimeUnit.SECONDS);
                assertNotNull(failure);
                assertEquals("23P01", failure.getSQLState());
                assertTrue(failure.getMessage().contains("no_cruce_espacio"));
                assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM asignacion", Integer.class));
            } finally {
                first.rollback();
                second.rollback();
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private long insertResource(String table, String name) {
        return jdbc.queryForObject("INSERT INTO " + table + " (nombre) VALUES (?) RETURNING id", Long.class, name);
    }

    private long insertAssignment(long espacio, long docente, String inicio, String fin) {
        return jdbc.queryForObject("""
                INSERT INTO asignacion (espacio_id, docente_id, inicio, fin) VALUES (?, ?, ?, ?) RETURNING id
                """, Long.class, espacio, docente, timestamp(inicio), timestamp(fin));
    }

    private static void insertAssignment(Connection connection, long espacio, long docente,
            String inicio, String fin) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO asignacion (espacio_id, docente_id, inicio, fin) VALUES (?, ?, ?, ?)
                """)) {
            statement.setLong(1, espacio);
            statement.setLong(2, docente);
            statement.setTimestamp(3, timestamp(inicio));
            statement.setTimestamp(4, timestamp(fin));
            statement.executeUpdate();
        }
    }

    private static Timestamp timestamp(String time) {
        return Timestamp.valueOf(LocalDateTime.parse("2026-10-26T" + time));
    }

    private static void assertSqlFailure(String expectedState, String constraint, Runnable operation) {
        var failure = assertThrows(DataAccessException.class, operation::run);
        Throwable cause = failure;
        while (!(cause instanceof SQLException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        assertTrue(cause instanceof SQLException, "Expected a JDBC SQL exception");
        var sqlFailure = (SQLException) cause;
        assertEquals(expectedState, sqlFailure.getSQLState());
        if (constraint != null) {
            assertTrue(sqlFailure.getMessage().contains(constraint), sqlFailure::getMessage);
        }
    }

    private static String requiredPassword() {
        var password = System.getenv("AULA_DB_PASSWORD");
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("Define AULA_DB_PASSWORD to run the PostgreSQL integration tests");
        }
        return password;
    }
}
