package com.carddemo.online.files;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;

import java.sql.Connection;

@Component
public class OnlineDemoData {
    private final JdbcTemplate jdbc;

    public OnlineDemoData(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void reset() {
        jdbc.execute((Connection connection) -> {
            ScriptUtils.executeSqlScript(connection,
                    new EncodedResource(new ClassPathResource("online/demo-seed.sql")));
            return null;
        });
    }
}
