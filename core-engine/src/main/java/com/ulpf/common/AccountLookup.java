package com.ulpf.common;

import com.ulpf.common.db.UserRepository;
import com.ulpf.controlplane.model.Role;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

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

    static final Duration TTL = Duration.ofSeconds(60);

    private record Entry(Optional<Account> account, long loadedAtNanos) {}

    private final UserRepository userRepository;
    // One entry per account id seen in a validly signed token, so its size is bounded by the number of accounts
    // (a control plane has hundreds, not millions); stale entries are reloaded, not piled up. No cache library needed.
    private final ConcurrentHashMap<String, Entry> cache = new ConcurrentHashMap<>();

    public AccountLookup(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /** The account as the database has it, at most {@link #TTL} old. Empty if the account no longer exists. */
    public Optional<Account> find(String userId) {
        if (userId == null) {
            return Optional.empty();
        }
        long now = System.nanoTime();
        Entry entry = cache.get(userId);
        if (entry == null || now - entry.loadedAtNanos() > TTL.toNanos()) {
            entry = new Entry(load(userId), now);
            cache.put(userId, entry);
        }
        return entry.account();
    }

    /** Drops the cached entry at once; call after revoking an account or changing its password. */
    public void evict(String userId) {
        if (userId != null) {
            cache.remove(userId);
        }
    }

    /**
     * Short, non-reversible fingerprint of a password hash, carried in login tokens as {@code pwv}.
     * A new password gives a new BCrypt hash, so the fingerprint changes and older tokens stop matching.
     * 16 base64url characters of SHA-256 (96 bits): small in every token, and reveals nothing about the hash.
     */
    public static String fingerprint(String passwordHash) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(passwordHash.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e); // every JVM ships it
        }
    }

    private Optional<Account> load(String userId) {
        return userRepository.findById(userId).map(user -> new Account(user.userId(), user.username(), user.role(),
                userRepository.isSuperAdmin(user.userId()), fingerprint(user.passwordHash())));
    }
}
