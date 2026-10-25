package com.aula.asistencial.aulaassistentapi.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.support.SpringFactoriesLoader;

class DotenvEnvironmentPostProcessorTests {

    @TempDir
    Path directory;

    @Test
    void loadsCredentialsAndPreservesQuotedSpecialCharacters() throws IOException {
        writeDotenv("# Local credentials\r\n"
                + "AULA_DB_URL=jdbc:postgresql://localhost:5432/dotenv_test\r\n"
                + "AULA_DB_USER=dotenv_user\r\n"
                + "AULA_DB_PASSWORD=\"test#=\\path!\"\r\n"
                + "EXTERNAL_SERVICE_NAME=local_service\r\n");
        var environment = environment(Map.of(), Map.of());

        process(environment);

        assertEquals("jdbc:postgresql://localhost:5432/dotenv_test",
                environment.getProperty("spring.datasource.url"));
        assertEquals("dotenv_user", environment.getProperty("spring.datasource.username"));
        assertEquals("test#=\\path!", environment.getProperty("spring.datasource.password"));
        assertEquals("local_service", environment.getProperty("external.service.name"));
    }

    @Test
    void dotenvOverridesImportedLocalAndPackagedConfiguration() throws IOException {
        writeDotenv("AULA_DB_PASSWORD=dotenv_password\n");
        var environment = environment(Map.of(), Map.of());
        environment.getPropertySources().addLast(new MapPropertySource("application-local.properties", Map.of(
                "AULA_DB_PASSWORD", "old_local_password",
                "spring.datasource.password", "local_datasource_password")));
        environment.getPropertySources().addLast(new MapPropertySource("application.properties", Map.of(
                "spring.datasource.password", "packaged_password")));

        process(environment);

        assertEquals("dotenv_password", environment.getProperty("spring.datasource.password"));
    }

    @Test
    void operatingSystemAliasesOverrideDotenv() throws IOException {
        writeDotenv("AULA_DB_URL=jdbc:postgresql://localhost:5432/file_database\n"
                + "AULA_DB_USER=file_user\nAULA_DB_PASSWORD=file_password\n");
        var environment = environment(Map.of(), Map.of(
                "AULA_DB_URL", "jdbc:postgresql://localhost:5432/environment_database",
                "AULA_DB_USER", "environment_user",
                "AULA_DB_PASSWORD", "environment_password"));

        process(environment);

        assertEquals("jdbc:postgresql://localhost:5432/environment_database",
                environment.getProperty("spring.datasource.url"));
        assertEquals("environment_user", environment.getProperty("spring.datasource.username"));
        assertEquals("environment_password", environment.getProperty("spring.datasource.password"));
    }

    @Test
    void standardSpringEnvironmentVariableOverridesDotenvAlias() throws IOException {
        writeDotenv("AULA_DB_PASSWORD=file_password\n");
        var environment = environment(Map.of(), Map.of("SPRING_DATASOURCE_PASSWORD", "spring_environment"));

        process(environment);

        assertEquals("spring_environment", environment.getProperty("spring.datasource.password"));
    }

    @Test
    void standardSpringNamesWorkInDotenvWithoutAliases() throws IOException {
        writeDotenv("SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/standard_names\n"
                + "SPRING_DATASOURCE_USERNAME=standard_user\n"
                + "SPRING_DATASOURCE_PASSWORD=standard_password\n");
        var environment = environment(Map.of(), Map.of());

        process(environment);

        assertEquals("jdbc:postgresql://localhost:5432/standard_names",
                environment.getProperty("spring.datasource.url"));
        assertEquals("standard_user", environment.getProperty("spring.datasource.username"));
        assertEquals("standard_password", environment.getProperty("spring.datasource.password"));
    }

    @Test
    void javaSystemPropertiesOverrideEnvironmentAndDotenv() throws IOException {
        writeDotenv("AULA_DB_PASSWORD=file_password\n");
        var environment = environment(Map.of("spring.datasource.password", "system_password"),
                Map.of("SPRING_DATASOURCE_PASSWORD", "environment_password"));

        process(environment);

        assertEquals("system_password", environment.getProperty("spring.datasource.password"));
    }

    @Test
    void commandLineOverridesSystemPropertiesEnvironmentAndDotenv() throws IOException {
        writeDotenv("AULA_DB_PASSWORD=file_password\n");
        var environment = environment(Map.of("spring.datasource.password", "system_password"),
                Map.of("SPRING_DATASOURCE_PASSWORD", "environment_password"));
        environment.getPropertySources().addFirst(new SimpleCommandLinePropertySource(
                "--spring.datasource.password=command_line_password"));

        process(environment);

        assertEquals("command_line_password", environment.getProperty("spring.datasource.password"));
    }

    @Test
    void mapsAliasFromHighestPrioritySource() throws IOException {
        writeDotenv("AULA_DB_PASSWORD=file_password\n");
        var environment = environment(Map.of("AULA_DB_PASSWORD", "system_password"),
                Map.of("AULA_DB_PASSWORD", "environment_password"));
        environment.getPropertySources().addFirst(new SimpleCommandLinePropertySource(
                "--AULA_DB_PASSWORD=command_line_password"));

        process(environment);

        assertEquals("command_line_password", environment.getProperty("spring.datasource.password"));
    }

    @Test
    void missingDotenvAllowsExistingConfiguration() {
        var environment = environment(Map.of(), Map.of());
        environment.getPropertySources().addLast(new MapPropertySource("application.properties", Map.of(
                "spring.datasource.password", "existing_password")));

        assertDoesNotThrow(() -> process(environment));

        assertEquals("existing_password", environment.getProperty("spring.datasource.password"));
    }

    @Test
    void operatingSystemAliasesWorkWithoutDotenv() {
        var environment = environment(Map.of(), Map.of("AULA_DB_PASSWORD", "environment_only_password"));

        process(environment);

        assertEquals("environment_only_password", environment.getProperty("spring.datasource.password"));
    }

    @Test
    void malformedDotenvFailsWithoutExposingTheOffendingContent() throws IOException {
        var syntheticSecret = "synthetic-secret-that-must-not-appear";
        writeDotenv("invalid entry " + syntheticSecret + "\n");

        var failure = assertThrows(IllegalStateException.class,
                () -> process(environment(Map.of(), Map.of())));

        assertTrue(failure.getMessage().contains(".env"));
        assertNull(failure.getCause(), "Parser errors may contain the original credential line");
        var trace = new StringWriter();
        failure.printStackTrace(new PrintWriter(trace));
        assertFalse(trace.toString().contains(syntheticSecret));
    }

    @Test
    void springBootDiscoversTheProcessorFromItsFactoriesRegistration() {
        var factories = SpringFactoriesLoader.loadFactoryNames(EnvironmentPostProcessor.class,
                DotenvEnvironmentPostProcessor.class.getClassLoader());

        assertTrue(factories.contains(DotenvEnvironmentPostProcessor.class.getName()),
                "The processor must load for main, bootRun and @SpringBootTest without a manual call");
    }

    private void writeDotenv(String content) throws IOException {
        Files.writeString(directory.resolve(".env"), content, StandardCharsets.UTF_8);
    }

    private void process(StandardEnvironment environment) {
        new DotenvEnvironmentPostProcessor(directory).postProcessEnvironment(environment,
                new SpringApplication(DotenvEnvironmentPostProcessorTests.class));
    }

    private static StandardEnvironment environment(Map<String, Object> systemProperties,
            Map<String, Object> systemEnvironment) {
        var environment = new StandardEnvironment();
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,
                new MapPropertySource(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,
                        new LinkedHashMap<>(systemProperties)));
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        new LinkedHashMap<>(systemEnvironment)));
        return environment;
    }
}
