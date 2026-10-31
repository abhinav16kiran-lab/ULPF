package com.ulpf.controlplane.service;

import org.springframework.http.HttpStatus;

/**
 * A refused account action (setup, admin links, revoke, password reset) with the HTTP status the API returns.
 * One exception type keeps every account endpoint's error mapping explicit and in one place
 * ({@code GlobalExceptionHandler}), instead of relying on what a generic exception happens to mean.
 */
public class AccountActionException extends RuntimeException {

    private final HttpStatus status;

    public AccountActionException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
