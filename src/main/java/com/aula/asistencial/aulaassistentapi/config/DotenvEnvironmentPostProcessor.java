package com.aula.asistencial.aulaassistentapi.config;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import io.github.cdimascio.dotenv.Dotenv;
import io.github.cdimascio.dotenv.DotenvException;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

public class DotenvEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private final Path directory;

    public DotenvEnvironmentPostProcessor() {
        this(Path.of("."));
    }

    DotenvEnvironmentPostProcessor(Path directory) {
        this.directory = directory;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Dotenv dotenv;
        try {
            dotenv = Dotenv.configure().directory(directory.toString()).ignoreIfMissing().load();
        } catch (DotenvException exception) {
            // El mensaje original puede contener la linea completa con una credencial.
            throw new IllegalStateException("No se pudo leer .env. Revisa los permisos y el formato CLAVE=valor.");
        }

        Map<String, Object> values = new HashMap<>();
        dotenv.entries(Dotenv.Filter.DECLARED_IN_ENV_FILE)
                .forEach(entry -> values.put(entry.getKey(), entry.getValue()));
        var propertySource = new SystemEnvironmentPropertySource("dotenv", values);
        var sources = environment.getPropertySources();
        if (sources.contains(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)) {
            sources.addAfter(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, propertySource);
        } else {
            sources.addLast(propertySource);
        }

        // Las propiedades actuales del datasource son literales: traducir las claves
        // AULA_DB_* permite reemplazarlas sin editar application.properties.
        addAlias(environment, values, "AULA_DB_URL", "spring.datasource.url");
        addAlias(environment, values, "AULA_DB_USER", "spring.datasource.username");
        addAlias(environment, values, "AULA_DB_PASSWORD", "spring.datasource.password");
    }

    private static void addAlias(ConfigurableEnvironment environment, Map<String, Object> values,
            String variable, String property) {
        var value = environment.getProperty(variable);
        if (value != null) {
            values.putIfAbsent(property, value);
        }
    }

    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }
}
