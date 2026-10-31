package com.ulpf.common;

import com.ulpf.common.db.UserRepository;
import com.ulpf.controlplane.model.Role;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * The live view of an account that {@link JwtFilter} checks on every authenticated request, so that a revoked
 * account or a changed password takes effect immediately instead of when the 24 h token expires.
 *
 * <p>Results are cached per user for 60 seconds (one database read per user per minute at most);
 * {@link #evict(String)} drops an entry at once when an account is revoked or its password changes.
 */
@Component
public class AccountLookup {

    /** What the filter needs: whether the account exists, its role, the super-admin flag and a password fingerprint. */
    public record Account(String userId, String username, Role role, boolean superAdmin, String passwordFingerprint) {}

    private final UserRepository userRepository;

    public AccountLookup(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public Optional<Account> find(String userId) {
        throw new UnsupportedOperationException("not implemented yet (admin accounts plan, step 4)");
    }

    public void evict(String userId) {
        throw new UnsupportedOperationException("not implemented yet (admin accounts plan, step 4)");
    }

    /**
     * Short, non-reversible fingerprint of a password hash, carried in login tokens as {@code pwv}.
     * A new password gives a new BCrypt hash, so the fingerprint changes and older tokens stop matching.
     */
    public static String fingerprint(String passwordHash) {
        throw new UnsupportedOperationException("not implemented yet (admin accounts plan, step 4)");
    }
}
