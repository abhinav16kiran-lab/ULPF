package com.ulpf.common.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

/**
 * Small, idempotent SQLite migrations that {@code schema.sql} cannot do, because
 * {@code CREATE TABLE IF NOT EXISTS} never changes a table that already exists.
 *
 * <p>Runs once per application start, after Spring has executed {@code schema.sql} (all singletons,
 * including the SQL initializer, exist) and before the web server accepts requests.
 *
 * <p>Admin accounts migration: adds {@code users.is_super_admin} and the "one super-admin" unique index,
 * and, in the same one-time step, removes the {@code ADMIN} rows left over from the old {@code .env} seeder.
 * The missing column is the marker: once it exists the step never runs again, so admins created later
 * (the super-admin, invited admins) survive every restart. A legacy admin that vendor data still points at is
 * not deleted (that would break the vendor's rows or stop the server starting); it is locked instead: role
 * {@code USER}, a password nobody knows, and a {@code WARN} so a person can decide.
 */
@Component
public class SqliteSchemaMigrator implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(SqliteSchemaMigrator.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbcTemplate;
    private final BCryptPasswordEncoder passwordEncoder;
    private final TransactionTemplate transaction;

    public SqliteSchemaMigrator(JdbcTemplate jdbcTemplate, BCryptPasswordEncoder passwordEncoder) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
        this.transaction = new TransactionTemplate(
                new DataSourceTransactionManager(Objects.requireNonNull(jdbcTemplate.getDataSource())));
    }

    @Override
    public void afterSingletonsInstantiated() {
        migrate();
    }

    public void migrate() {
        if (!columnExists("users", "is_super_admin")) {
            // One transaction: SQLite DDL is transactional, so a crash half way leaves the column missing
            // and the whole step simply runs again on the next start.
            transaction.executeWithoutResult(status -> {
                jdbcTemplate.execute("ALTER TABLE users ADD COLUMN is_super_admin INTEGER NOT NULL DEFAULT 0 CHECK (is_super_admin IN (0, 1))");
                removeLegacyAdmins();
            });
        }
        jdbcTemplate.execute("CREATE UNIQUE INDEX IF NOT EXISTS one_super_admin ON users(is_super_admin) WHERE is_super_admin = 1");
    }

    private void removeLegacyAdmins() {
        List<String[]> admins = jdbcTemplate.query("SELECT user_id, username FROM users WHERE role = 'ADMIN'",
                (rs, i) -> new String[]{rs.getString("user_id"), rs.getString("username")});
        for (String[] admin : admins) {
            String userId = admin[0];
            String username = admin[1];
            List<String> vendors = jdbcTemplate.queryForList(
                    "SELECT vendor_id FROM vendors WHERE owner_user_id = ?", String.class, userId);
            List<String> requests = jdbcTemplate.queryForList(
                    "SELECT request_id FROM onboarding_requests WHERE user_id = ?", String.class, userId);
            if (vendors.isEmpty() && requests.isEmpty()) {
                jdbcTemplate.update("DELETE FROM notifications WHERE user_id = ?", userId);
                jdbcTemplate.update("DELETE FROM users WHERE user_id = ?", userId);
                log.warn("Admin accounts upgrade: removed legacy admin '{}' ({}) and its notifications", username, userId);
            } else {
                jdbcTemplate.update("UPDATE users SET role = 'USER', password_hash = ? WHERE user_id = ?",
                        passwordEncoder.encode(unguessableSecret()), userId);
                log.warn("Admin accounts upgrade: legacy admin '{}' ({}) is referenced by vendors {} and onboarding requests {}; "
                        + "locked (role USER, unknown password) instead of deleted. Review it by hand.",
                        username, userId, vendors, requests);
            }
        }
    }

    private boolean columnExists(String table, String column) {
        return jdbcTemplate.query("PRAGMA table_info(" + table + ")", (rs, i) -> rs.getString("name")).contains(column);
    }

    private static String unguessableSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
