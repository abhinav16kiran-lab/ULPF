package com.ulpf.controlplane.service;

import com.ulpf.common.db.UserRepository;
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

    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    public SuperAdminRecoveryService(UserRepository userRepository, BCryptPasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public void resetPassword(String password, String confirmPassword) {
        throw new UnsupportedOperationException("not implemented yet (admin accounts plan, step 5)");
    }
}
