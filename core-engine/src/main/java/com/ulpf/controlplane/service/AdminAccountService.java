package com.ulpf.controlplane.service;

import com.ulpf.common.AccountLookup;
import com.ulpf.common.db.UserRepository;
import com.ulpf.controlplane.model.Role;
import com.ulpf.controlplane.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * The super-admin's view of admin accounts: list them and revoke regular admins.
 *
 * <p>Revoking deletes the account and its notifications (a removed admin leaves no account behind) and evicts it
 * from {@link AccountLookup}, so its existing login tokens stop working on the next request. The super-admin
 * cannot be revoked, and an account that vendor data points at is never deleted (that would destroy the vendor's data).
 */
@Service
public class AdminAccountService {

    public record AdminSummary(String userId, String username, boolean superAdmin, LocalDateTime createdAt) {}

    private static final Logger log = LoggerFactory.getLogger(AdminAccountService.class);

    private final UserRepository userRepository;
    private final AccountLookup accountLookup;

    public AdminAccountService(UserRepository userRepository, AccountLookup accountLookup) {
        this.userRepository = userRepository;
        this.accountLookup = accountLookup;
    }

    /** Every admin, super-admin first, then oldest first. Two queries, whatever the number of admins. */
    public List<AdminSummary> listAdmins() {
        String superAdminId = userRepository.findSuperAdmin().map(User::userId).orElse(null);
        return userRepository.findUsersByRole(Role.ADMIN).stream()
                .map(u -> new AdminSummary(u.userId(), u.username(), u.userId().equals(superAdminId), u.createdAt()))
                .sorted(Comparator.comparing(AdminSummary::superAdmin).reversed()
                        .thenComparing(AdminSummary::createdAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(AdminSummary::username))
                .toList();
    }

    /**
     * Deletes a regular admin with its notifications and links. Refused: caller is not the super-admin (403),
     * target is the super-admin (409), target is not an admin (404), vendor data points at the target (409).
     */
    public void revoke(String superAdminUserId, String targetUserId) {
        requireSuperAdmin(userRepository, superAdminUserId);
        if (userRepository.isSuperAdmin(targetUserId)) {
            throw new AccountActionException(HttpStatus.CONFLICT, "The super-admin cannot be revoked");
        }
        User target = userRepository.findById(targetUserId)
                .filter(u -> u.role() == Role.ADMIN)
                .orElseThrow(() -> new AccountActionException(HttpStatus.NOT_FOUND, "No such admin"));
        if (userRepository.isReferencedByVendorData(target.userId())) {
            throw new AccountActionException(HttpStatus.CONFLICT,
                    "This admin owns vendor data from before the admin accounts change; it cannot be deleted from here");
        }
        userRepository.deleteUserWithNotifications(target.userId());
        accountLookup.evict(target.userId());
        log.warn("Admin '{}' revoked by the super-admin", target.username());
    }

    /** Shared by the admin services: the caller must be the super-admin (403 otherwise). */
    static void requireSuperAdmin(UserRepository userRepository, String userId) {
        if (userId == null || !userRepository.isSuperAdmin(userId)) {
            throw new AccountActionException(HttpStatus.FORBIDDEN, "Only the super-admin can manage admins");
        }
    }
}
