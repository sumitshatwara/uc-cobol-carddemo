package com.carddemo.app;

import com.carddemo.common.repo.RepoPaths;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Serves the built operations console (ui/dist) from the same port as the API when it exists. */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final Path dist;

    public WebConfig(RepoPaths repo) {
        this.dist = repo.root().resolve("ui/dist");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        if (Files.isDirectory(dist)) {
            registry.addResourceHandler("/**").addResourceLocations(dist.toUri().toString());
        }
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        if (Files.isDirectory(dist)) {
            registry.addViewController("/").setViewName("forward:/index.html");
        }
    }
}
