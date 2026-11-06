package com.ulpf.controlplane.service;

import com.ulpf.common.db.UserRepository;
import com.ulpf.controlplane.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Resets the super-admin's password when it is lost. Deliberately has no HTTP endpoint: it is run only by the
 * server-side command ({@code startup/reset-super-admin.sh}), so only someone who can run container commands on
 * the host (and can already read the database and {@code .env}) can use it. A reset changes the password
 * fingerprint, so the super-admin's existing login tokens stop working.
 */
@Service
public class SuperAdminRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(SuperAdminRecoveryService.class);

    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    public SuperAdminRecoveryService(UserRepository userRepository, BCryptPasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /** Sets the super-admin's new password and returns the super-admin's username. */
    public String resetPassword(String password, String confirmPassword) {
        User superAdmin = userRepository.findSuperAdmin().orElseThrow(() -> new AccountActionException(
                HttpStatus.CONFLICT, "There is no super-admin yet: finish the one-time setup in the browser"));
        CredentialRules.validateAdminPassword(password, confirmPassword);
        userRepository.updatePassword(superAdmin.userId(), passwordEncoder.encode(password));
        log.warn("Super-admin password for '{}' was reset from the server command line", superAdmin.username());
        return superAdmin.username();
    }
}
