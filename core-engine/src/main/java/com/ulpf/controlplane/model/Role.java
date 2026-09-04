package com.ulpf.controlplane.model;

public enum Role {
    ADMIN,
    VENDOR,
    USER;

    public boolean isAtLeast(Role requiredRole) {
        if (this == ADMIN) return true;
        if (this == VENDOR) return requiredRole == VENDOR || requiredRole == USER;
        if (this == USER) return requiredRole == USER;
        return false;
    }

    public boolean satisfiesRequestedRole(String requestedRoleStr) {
        if (requestedRoleStr == null || requestedRoleStr.isBlank()) {
            return true;
        }
        try {
            Role requested = Role.valueOf(requestedRoleStr.trim().toUpperCase());
            return this.isAtLeast(requested);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
