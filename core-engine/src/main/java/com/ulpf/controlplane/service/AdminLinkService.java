package com.ulpf.controlplane.service;

import com.ulpf.common.AccountLookup;
import com.ulpf.common.db.AdminLinkRepository;
import com.ulpf.common.db.AdminLinkRepository.AdminLink;
import com.ulpf.common.db.AdminLinkRepository.Kind;
import com.ulpf.common.db.UserRepository;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Single-use admin links, created by the super-admin and shared by hand (there is no email in an air-gapped
 * deployment): an INVITE link creates a regular admin; a RESET link sets a new password for one regular admin.
 *
 * <p>Links live 12 hours and work once. The raw token is returned only when the link is created; the database
 * keeps its SHA-256 hash, like vendor API keys. Unknown, expired, used and cancelled links are all reported
 * the same way (410), so a caller learns nothing about which links exist.
 */
@Service
public class AdminLinkService {

    public static final Duration LINK_LIFETIME = Duration.ofHours(12);

    /** Returned once, at creation: the raw token goes into the link the super-admin shares. */
    public record CreatedLink(String linkId, Kind kind, String token, Instant expiresAt) {}

    /** What the link page shows before the person fills in the form. */
    public record LinkInfo(Kind kind, String label, String targetUsername, Instant expiresAt) {}

    /** Form input when using a link. {@code username} is used by INVITE links only. */
    public record LinkUse(String username, String password, String confirmPassword) {}

    private final AdminLinkRepository linkRepository;
    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final AccountLookup accountLookup;
    private final Clock clock;

    public AdminLinkService(AdminLinkRepository linkRepository, UserRepository userRepository,
                            BCryptPasswordEncoder passwordEncoder, AccountLookup accountLookup, Clock clock) {
        this.linkRepository = linkRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.accountLookup = accountLookup;
        this.clock = clock;
    }

    public CreatedLink createInvite(String superAdminUserId, String label) {
        throw new UnsupportedOperationException("not implemented yet (admin accounts plan, step 5)");
    }

    public CreatedLink createReset(String superAdminUserId, String targetUserId) {
        throw new UnsupportedOperationException("not implemented yet (admin accounts plan, step 5)");
    }

    public LinkInfo describe(String token) {
        throw new UnsupportedOperationException("not implemented yet (admin accounts plan, step 5)");
    }

    public void use(String token, LinkUse use) {
        throw new UnsupportedOperationException("not implemented yet (admin accounts plan, step 5)");
    }

    public List<AdminLink> pending() {
        throw new UnsupportedOperationException("not implemented yet (admin accounts plan, step 5)");
    }

    public void cancel(String linkId) {
        throw new UnsupportedOperationException("not implemented yet (admin accounts plan, step 5)");
    }
}
