package com.ulpf.controlplane.service;

import com.ulpf.common.AccountLookup;
import com.ulpf.common.db.UserRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
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

    private final UserRepository userRepository;
    private final AccountLookup accountLookup;

    public AdminAccountService(UserRepository userRepository, AccountLookup accountLookup) {
        this.userRepository = userRepository;
        this.accountLookup = accountLookup;
    }

    public List<AdminSummary> listAdmins() {
        throw new UnsupportedOperationException("not implemented yet (admin accounts plan, step 5)");
    }

    public void revoke(String superAdminUserId, String targetUserId) {
        throw new UnsupportedOperationException("not implemented yet (admin accounts plan, step 5)");
    }
}
