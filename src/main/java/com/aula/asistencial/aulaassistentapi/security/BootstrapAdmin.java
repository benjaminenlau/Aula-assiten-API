package com.aula.asistencial.aulaassistentapi.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "aula.bootstrap.enabled", havingValue = "true", matchIfMissing = true)
public class BootstrapAdmin implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(BootstrapAdmin.class);
    private final AuthService auth;
    private final Environment environment;

    public BootstrapAdmin(AuthService auth, Environment environment) {
        this.auth = auth;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        var username = environment.getProperty("AULA_BOOTSTRAP_ADMIN_USER");
        var password = environment.getProperty("AULA_BOOTSTRAP_ADMIN_PASSWORD");
        if ((username == null || username.isBlank()) && (password == null || password.isBlank())) {
            LOG.info("Bootstrap omitido: configura AULA_BOOTSTRAP_ADMIN_USER y AULA_BOOTSTRAP_ADMIN_PASSWORD "
                    + "para crear el primer administrador. No se habilitan accesos anonimos.");
            return;
        }
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new IllegalStateException("El administrador inicial requiere ambas variables AULA_BOOTSTRAP_ADMIN_USER "
                    + "y AULA_BOOTSTRAP_ADMIN_PASSWORD.");
        }
        auth.bootstrapAdmin(username, password);
    }
}
