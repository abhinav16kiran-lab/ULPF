package com.ulpf.controlplane.service;

import com.ulpf.common.AccountLookup;
import com.ulpf.common.db.AdminLinkRepository;
import com.ulpf.common.db.AdminLinkRepository.AdminLink;
import com.ulpf.common.db.AdminLinkRepository.Kind;
import com.ulpf.common.db.UserRepository;
import com.ulpf.controlplane.model.Role;
import com.ulpf.controlplane.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
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
    static final int LABEL_MAX = 100;
    private static final int TOKEN_BYTES = 32; // 256 random bits: unguessable, so a fast SHA-256 hash is enough
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Logger log = LoggerFactory.getLogger(AdminLinkService.class);

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
        AdminAccountService.requireSuperAdmin(userRepository, superAdminUserId);
        String cleanLabel = label == null || label.isBlank() ? null : label.trim();
        if (cleanLabel != null && cleanLabel.length() > LABEL_MAX) {
            throw new AccountActionException(HttpStatus.BAD_REQUEST, "Label must be at most " + LABEL_MAX + " characters");
        }
        return create(Kind.INVITE, cleanLabel, null, superAdminUserId);
    }

    /** A reset link for a regular admin. The super-admin's own password is reset only on the server. */
    public CreatedLink createReset(String superAdminUserId, String targetUserId) {
        AdminAccountService.requireSuperAdmin(userRepository, superAdminUserId);
        if (userRepository.isSuperAdmin(targetUserId)) {
            throw new AccountActionException(HttpStatus.CONFLICT,
                    "The super-admin's password is reset on the server with startup/reset-super-admin.sh");
        }
        User target = userRepository.findById(targetUserId)
                .filter(u -> u.role() == Role.ADMIN)
                .orElseThrow(() -> new AccountActionException(HttpStatus.NOT_FOUND, "No such admin"));
        return create(Kind.RESET, null, target.userId(), superAdminUserId);
    }

    public LinkInfo describe(String token) {
        AdminLink link = usableLink(token);
        String targetUsername = link.kind() == Kind.RESET ? resetTarget(link).username() : null;
        return new LinkInfo(link.kind(), link.label(), targetUsername, link.expiresAt());
    }

    /**
     * Uses the link. The form is checked first, so a rejected attempt (taken username, bad password) does not
     * use up the link. The slow BCrypt hash is computed before the first write: SQLite takes its write lock at the
     * first write of a transaction, so the lock covers only the two writes. Those two run in one transaction:
     * claim the link (conditional UPDATE: of two simultaneous uses only one wins), then create the admin or set
     * the password. If the account write fails, the claim is rolled back too.
     */
    @Transactional
    public void use(String token, LinkUse use) {
        AdminLink link = usableLink(token);
        String username = null;
        User target = null;
        if (link.kind() == Kind.INVITE) {
            username = CredentialRules.validateUsername(use.username());
            CredentialRules.validateAdminPassword(use.password(), use.confirmPassword());
            if (userRepository.existsByUsername(username)) {
                throw usernameTaken();
            }
        } else {
            target = resetTarget(link);
            CredentialRules.validateAdminPassword(use.password(), use.confirmPassword());
        }
        String passwordHash = passwordEncoder.encode(use.password());

        if (!linkRepository.markUsed(link.linkId(), clock.instant())) {
            throw gone();
        }
        if (link.kind() == Kind.INVITE) {
            try {
                userRepository.save(new User(null, username, username, passwordHash, Role.ADMIN, null));
            } catch (DuplicateKeyException e) {
                throw usernameTaken(); // someone took the name between the check and the insert
            }
            log.info("Admin '{}' created from an invite link", username);
        } else {
            userRepository.updatePassword(target.userId(), passwordHash);
            accountLookup.evict(target.userId()); // old sessions end on their next request
            log.info("Password of admin '{}' reset from a reset link", target.username());
        }
    }

    /** Links that are neither used nor expired, oldest first. */
    public List<AdminLink> pending() {
        return linkRepository.findPending(clock.instant());
    }

    public void cancel(String linkId) {
        linkRepository.delete(linkId);
    }

    private CreatedLink create(Kind kind, String label, String targetUserId, String createdBy) {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant now = clock.instant();
        AdminLink saved = linkRepository.save(new AdminLink(null, kind, OnboardingService.hashSha256(token), label,
                targetUserId, createdBy, now, now.plus(LINK_LIFETIME), null));
        return new CreatedLink(saved.linkId(), kind, token, saved.expiresAt());
    }

    /** The link for this token if it can still be used; otherwise 410, the same answer for every reason. */
    private AdminLink usableLink(String token) {
        if (token == null || token.isBlank()) {
            throw gone();
        }
        Instant now = clock.instant();
        return linkRepository.findByTokenHash(OnboardingService.hashSha256(token))
                .filter(link -> link.usedAt() == null && now.isBefore(link.expiresAt()))
                .orElseThrow(AdminLinkService::gone);
    }

    /** A reset link's target, if it is still a regular admin (it may have been revoked since). */
    private User resetTarget(AdminLink link) {
        return userRepository.findById(link.targetUserId())
                .filter(u -> u.role() == Role.ADMIN && !userRepository.isSuperAdmin(u.userId()))
                .orElseThrow(AdminLinkService::gone);
    }

    private static AccountActionException gone() {
        return new AccountActionException(HttpStatus.GONE, "This link is no longer valid. Ask the super-admin for a new one.");
    }

    private static AccountActionException usernameTaken() {
        return new AccountActionException(HttpStatus.CONFLICT, "Username is already taken");
    }
}
