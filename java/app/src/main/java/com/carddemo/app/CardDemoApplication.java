package com.carddemo.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.Arrays;

@SpringBootApplication(scanBasePackages = "com.carddemo")
public class CardDemoApplication {
    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(CardDemoApplication.class);
        if (Arrays.stream(args).anyMatch(argument -> argument.startsWith("--carddemo.cli="))) {
            application.setWebApplicationType(WebApplicationType.NONE);
        }
        ConfigurableApplicationContext context = application.run(args);
        if (context.getEnvironment().containsProperty("carddemo.cli")) {
            int exitCode = SpringApplication.exit(context);
            if (exitCode != 0) {
                System.exit(exitCode);
            }
        }
    }
}
