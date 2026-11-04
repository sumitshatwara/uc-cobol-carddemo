package com.carddemo.app;

import com.carddemo.common.estate.ChainConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AppConfiguration {
    @Bean
    ChainConfig chainConfig() {
        return new ChainConfig();
    }
}
