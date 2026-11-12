package com.ulpf.controlplane.service;

import org.springframework.http.HttpStatus;

/**
 * A refused API key request: carries the HTTP status and a stable code ({@code NOT_OWNER}, {@code SOURCE_NOT_FOUND},
 * {@code SOURCE_NOT_ACTIVE}, {@code KEY_EXISTS}) so the frontend can react without parsing the message.
 */
public class ApiKeyException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiKeyException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
