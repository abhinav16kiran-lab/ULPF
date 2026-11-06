package com.ulpf.controlplane.service;

import org.springframework.http.HttpStatus;

import java.util.regex.Pattern;

/**
 * Username and password rules for admin accounts, shared by setup, invite links, reset links and the
 * super-admin reset command. Admin passwords: 12-100 characters (USER and VENDOR accounts keep 6-100,
 * enforced in {@link AuthService}). Usernames follow the same rule as sign-up. Throws
 * {@link AccountActionException} (400) on a violation.
 */
public final class CredentialRules {

    public static final int ADMIN_PASSWORD_MIN = 12;
    public static final int PASSWORD_MAX = 100;
    private static final Pattern USERNAME = Pattern.compile("^[a-zA-Z0-9_.-]{3,30}$");

    private CredentialRules() {}

    /** Returns the trimmed username if it is valid. */
    public static String validateUsername(String username) {
        String clean = username == null ? "" : username.trim();
        if (!USERNAME.matcher(clean).matches()) {
            throw invalid("Username must be 3-30 characters: letters, numbers, underscores, hyphens and periods");
        }
        return clean;
    }

    public static void validateAdminPassword(String password, String confirmPassword) {
        if (password == null || password.length() < ADMIN_PASSWORD_MIN || password.length() > PASSWORD_MAX) {
            throw invalid("Admin passwords must be " + ADMIN_PASSWORD_MIN + "-" + PASSWORD_MAX + " characters");
        }
        if (!password.equals(confirmPassword)) {
            throw invalid("Passwords do not match");
        }
    }

    private static AccountActionException invalid(String message) {
        return new AccountActionException(HttpStatus.BAD_REQUEST, message);
    }
}
