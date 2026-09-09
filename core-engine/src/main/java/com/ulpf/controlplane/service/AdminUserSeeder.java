package com.ulpf.controlplane.service;

import com.ulpf.common.db.UserRepository;
import com.ulpf.controlplane.model.Role;
import com.ulpf.controlplane.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Startup seeder to ensure a system administrator account exists with configured credentials.
 * Credentials are loaded dynamically from environment variables ULPF_ADMIN_USERNAME and ULPF_ADMIN_PASSWORD.
 */
@Component
public class AdminUserSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminUserSeeder.class);

    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final String adminUsername;
    private final String adminPassword;

    public AdminUserSeeder(
            UserRepository userRepository,
            BCryptPasswordEncoder passwordEncoder,
            @Value("${ulpf.admin.username:admin}") String adminUsername,
            @Value("${ulpf.admin.password:Admin@12345}") String adminPassword
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.adminUsername = adminUsername;
        this.adminPassword = adminPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (adminUsername == null || adminUsername.isBlank() || adminPassword == null || adminPassword.isBlank()) {
            log.warn("Admin username or password not provided in environment; skipping AdminUserSeeder.");
            return;
        }

        String cleanUsername = adminUsername.trim();
        Optional<User> existingUser = userRepository.findByUsername(cleanUsername);

        if (existingUser.isEmpty()) {
            log.info("Seeding initial System Administrator account for username '{}'...", cleanUsername);
            String hashedPassword = passwordEncoder.encode(adminPassword);
            User adminUser = new User(
                    UUID.randomUUID().toString(),
                    cleanUsername,
                    "System Administrator",
                    hashedPassword,
                    Role.ADMIN,
                    null
            );
            userRepository.save(adminUser);
            log.info("System Administrator account successfully seeded for username '{}'.", cleanUsername);
        } else {
            User user = existingUser.get();
            if (!passwordEncoder.matches(adminPassword, user.passwordHash()) || user.role() != Role.ADMIN) {
                log.info("Updating existing user '{}' to ensure ADMIN role and configured environment password match...", cleanUsername);
                String hashedPassword = passwordEncoder.encode(adminPassword);
                userRepository.updatePasswordAndRole(user.userId(), hashedPassword, Role.ADMIN);
                log.info("Successfully updated credentials and ADMIN role for user '{}'.", cleanUsername);
            } else {
                log.info("System Administrator account for username '{}' is active and up to date.", cleanUsername);
            }
        }
    }
}
