package com.ulpf.controlplane.model;

import java.time.LocalDateTime;

public record User(
    String userId,
    String username,
    String name,
    String passwordHash,
    Role role,
    LocalDateTime createdAt
) {}
