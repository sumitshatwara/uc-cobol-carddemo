package com.carddemo.common;

import com.carddemo.common.codec.CopybookParser;
import com.carddemo.common.repo.RepoPaths;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Shared beans of the foundation module. */
@Configuration
public class CommonConfig {

    @Bean
    public CopybookParser copybookParser(RepoPaths repo) {
        return new CopybookParser(repo.root());
    }
}
