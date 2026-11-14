package com.carddemo.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * CardDemo Java 21 service.  Scans every slice under {@code com.carddemo}: a slice adds a
 * {@link com.carddemo.common.run.ChainHandler} bean (and REST controllers / Flyway scripts in
 * its own range) without touching this module.
 */
@SpringBootApplication(scanBasePackages = "com.carddemo")
@EntityScan("com.carddemo")
@EnableJpaRepositories("com.carddemo")
public class CarddemoApplication {

    public static void main(String[] args) {
        var ctx = SpringApplication.run(CarddemoApplication.class, args);
        if (!ctx.getEnvironment().getProperty("carddemo.cli.mode", "").isBlank()) {
            System.exit(SpringApplication.exit(ctx)); // CLI mode: exit with ParityCli's code
        }
    }
}
