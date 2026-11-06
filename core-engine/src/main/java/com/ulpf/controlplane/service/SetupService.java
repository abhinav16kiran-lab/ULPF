package com.ulpf.controlplane.service;

import com.ulpf.common.db.UserRepository;
import com.ulpf.controlplane.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * One-time setup: creates the super-admin. Works only while no super-admin exists ("setup mode").
 *
 * <p>The person doing setup must first prove they control the server by entering the setup credentials
 * from {@code .env} ({@code ULPF_ADMIN_USERNAME} / {@code ULPF_ADMIN_PASSWORD}); otherwise anyone who
 * reaches the UI before the operator could claim the instance. After setup those values are never used
 * for accounts again: the super-admin lives only in SQLite.
 */
@Service
public class SetupService {

    public record SetupRequest(
            String setupUsername,
            String setupPassword,
            String username,
            String password,
            String confirmPassword
    ) {}

    private static final Logger log = LoggerFactory.getLogger(SetupService.class);

    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final String configuredSetupUsername;
    private final String configuredSetupPassword;

    public SetupService(
            UserRepository userRepository,
            BCryptPasswordEncoder passwordEncoder,
            @Value("${ulpf.admin.username:}") String configuredSetupUsername,
            @Value("${ulpf.admin.password:}") String configuredSetupPassword
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.configuredSetupUsername = configuredSetupUsername == null ? "" : configuredSetupUsername;
        this.configuredSetupPassword = configuredSetupPassword == null ? "" : configuredSetupPassword;
    }

    public boolean isSetupRequired() {
        return !userRepository.superAdminExists();
    }

    /**
     * Creates the super-admin. Order of checks: already done (409), setup disabled because {@code .env} has no
     * setup credentials (409, fail closed), wrong setup credentials (401), invalid new credentials (400).
     */
    public User completeSetup(SetupRequest request) {
        if (userRepository.superAdminExists()) {
            throw alreadyDone();
        }
        if (configuredSetupUsername.isBlank() || configuredSetupPassword.isBlank()) {
            throw new AccountActionException(HttpStatus.CONFLICT,
                    "Setup is disabled: set ULPF_ADMIN_USERNAME and ULPF_ADMIN_PASSWORD in .env and restart");
        }
        // Evaluate both comparisons (non-short-circuit &) in constant time, so timing reveals nothing.
        if (!(constantTimeEquals(request.setupUsername(), configuredSetupUsername)
                & constantTimeEquals(request.setupPassword(), configuredSetupPassword))) {
            throw new AccountActionException(HttpStatus.UNAUTHORIZED, "Wrong setup credentials");
        }
        String username = CredentialRules.validateUsername(request.username());
        CredentialRules.validateAdminPassword(request.password(), request.confirmPassword());

        try {
            User superAdmin = userRepository.createSuperAdmin(username, "Super-admin", passwordEncoder.encode(request.password()));
            log.info("One-time setup complete: super-admin '{}' created", username);
            return superAdmin;
        } catch (DuplicateKeyException e) {
            // Either a second setup won the race (unique index) or the username belongs to another account.
            if (userRepository.superAdminExists()) {
                throw alreadyDone();
            }
            throw new AccountActionException(HttpStatus.CONFLICT, "Username is already taken");
        }
    }

    private static AccountActionException alreadyDone() {
        return new AccountActionException(HttpStatus.CONFLICT, "Setup is already complete");
    }

    private static boolean constantTimeEquals(String given, String expected) {
        byte[] a = (given == null ? "" : given).getBytes(StandardCharsets.UTF_8);
        byte[] b = expected.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(a, b);
    }
}
