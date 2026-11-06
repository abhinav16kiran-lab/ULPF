package com.ulpf.controlplane.cli;

import com.ulpf.common.db.UserRepository;
import com.ulpf.controlplane.service.AccountActionException;
import com.ulpf.controlplane.service.SuperAdminRecoveryService;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * One-shot command that resets a lost super-admin password; {@code startup/reset-super-admin.sh} runs it inside
 * the core-engine container. It opens only the SQLite file ({@code SQLITE_DB_PATH}), not the Spring application
 * (no web server, ClickHouse or embedding model), reads the new password twice from stdin, and exits 0 or 1.
 * Run it with: {@code java -cp app.jar -Dloader.main=com.ulpf.controlplane.cli.ResetSuperAdminCommand
 * org.springframework.boot.loader.launch.PropertiesLauncher}.
 */
public final class ResetSuperAdminCommand {

    private ResetSuperAdminCommand() {}

    public static void main(String[] args) {
        quietLogging();
        String dbPath = System.getenv().getOrDefault("SQLITE_DB_PATH", "./data/control-plane.db");
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource("jdbc:sqlite:" + dbPath + "?foreign_keys=on", true);
        dataSource.setDriverClassName("org.sqlite.JDBC");
        BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        int exitCode = run(new JdbcTemplate(dataSource), new BCryptPasswordEncoder(), stdin, System.out);
        dataSource.destroy();
        System.exit(exitCode);
    }

    /** Reads password and confirmation (one line each), resets, reports. Never prints a password. */
    static int run(JdbcTemplate jdbc, BCryptPasswordEncoder encoder, BufferedReader in, PrintStream out) {
        try {
            String password = in.readLine();
            String confirm = in.readLine();
            String username = new SuperAdminRecoveryService(new UserRepository(jdbc), encoder).resetPassword(password, confirm);
            out.println("Password of super-admin '" + username + "' was reset. Its old sessions end within a minute.");
            return 0;
        } catch (AccountActionException e) {
            out.println("Not reset: " + e.getMessage());
            return 1;
        } catch (IOException e) {
            out.println("Not reset: could not read the password");
            return 1;
        }
    }

    /** Outside Spring Boot, Logback would log every SQL statement at DEBUG; keep the output to what matters. */
    private static void quietLogging() {
        if (LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) instanceof ch.qos.logback.classic.Logger root) {
            root.setLevel(ch.qos.logback.classic.Level.WARN);
        }
    }
}
