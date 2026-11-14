package com.carddemo.trantype.api;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only view of the Db2 transaction type table (after the latest run). */
@RestController
@RequestMapping("/api/trantype")
public class TrantypeController {

    private final JdbcTemplate jdbc;

    public TrantypeController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Rows of carddemo.transaction_type in key order; descriptions keep their stored padding. */
    @GetMapping("/types")
    public List<Map<String, Object>> types() {
        return jdbc.queryForList("SELECT tr_type AS \"trType\", tr_description AS \"trDescription\" "
                + "FROM carddemo.transaction_type ORDER BY tr_type");
    }
}
