package com.ulpf.controlplane.service;

/**
 * Username and password rules for admin accounts, shared by setup, invite links, reset links and the
 * super-admin reset command. Admin passwords: 12-100 characters (USER and VENDOR accounts keep 6-100,
 * enforced in {@link AuthService}). Throws {@link AccountActionException} (400) on a violation.
 */
public final class CredentialRules {

    public static final int ADMIN_PASSWORD_MIN = 12;
    public static final int PASSWORD_MAX = 100;

    private CredentialRules() {}

    public static String validateUsername(String username) {
        // not implemented yet (admin accounts plan, step 3)
        return username;
    }

    public static void validateAdminPassword(String password, String confirmPassword) {
        // not implemented yet (admin accounts plan, step 3)
    }
}
