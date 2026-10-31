package com.ulpf.common.db;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Small, idempotent SQLite migrations that {@code schema.sql} cannot do, because
 * {@code CREATE TABLE IF NOT EXISTS} never changes a table that already exists.
 *
 * <p>Runs once per application start, after Spring has executed {@code schema.sql} (all singletons,
 * including the SQL initializer, exist) and before the web server accepts requests.
 *
 * <p>Admin accounts migration: adds {@code users.is_super_admin} and the "one super-admin" unique index,
 * and, in the same one-time step, removes the {@code ADMIN} rows left over from the old {@code .env} seeder.
 */
@Component
public class SqliteSchemaMigrator implements SmartInitializingSingleton {

    private final JdbcTemplate jdbcTemplate;
    private final BCryptPasswordEncoder passwordEncoder;

    public SqliteSchemaMigrator(JdbcTemplate jdbcTemplate, BCryptPasswordEncoder passwordEncoder) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void afterSingletonsInstantiated() {
        migrate();
    }

    public void migrate() {
        // not implemented yet (admin accounts plan, step 2)
    }
}
