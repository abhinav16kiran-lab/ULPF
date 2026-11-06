package com.ulpf.testsupport;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.util.List;

/** Throwaway in-memory SQLite databases for tests: real schema, foreign keys on, one shared connection. */
public final class TestSqlite {

    private TestSqlite() {}

    /** A fresh database built from a classpath SQL script, e.g. {@code "sqlite/schema.sql"}. */
    public static JdbcTemplate inMemory(String schemaResource) {
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite::memory:?foreign_keys=on");
        dataSource.setSuppressClose(true);
        new ResourceDatabasePopulator(new ClassPathResource(schemaResource)).execute(dataSource);
        return new JdbcTemplate(dataSource);
    }

    public static List<String> columns(JdbcTemplate jdbc, String table) {
        return jdbc.query("PRAGMA table_info(" + table + ")", (rs, i) -> rs.getString("name"));
    }

    public static int count(JdbcTemplate jdbc, String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }
}
