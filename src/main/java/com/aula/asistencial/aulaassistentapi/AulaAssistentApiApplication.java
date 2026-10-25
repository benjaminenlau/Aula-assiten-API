package com.aula.asistencial.aulaassistentapi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class AulaAssistentApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(AulaAssistentApiApplication.class, args);
    }

}
