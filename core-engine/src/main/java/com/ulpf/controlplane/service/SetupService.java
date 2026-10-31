package com.ulpf.controlplane.service;

import com.ulpf.common.db.UserRepository;
import com.ulpf.controlplane.model.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

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
        this.configuredSetupUsername = configuredSetupUsername;
        this.configuredSetupPassword = configuredSetupPassword;
    }

    public boolean isSetupRequired() {
        throw new UnsupportedOperationException("not implemented yet (admin accounts plan, step 3)");
    }

    public User completeSetup(SetupRequest request) {
        throw new UnsupportedOperationException("not implemented yet (admin accounts plan, step 3)");
    }
}
