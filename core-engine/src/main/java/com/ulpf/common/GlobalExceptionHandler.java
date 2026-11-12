package com.ulpf.common;

import java.util.Map;

import com.ulpf.controlplane.service.AccountActionException;
import com.ulpf.controlplane.service.ApiKeyException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * Maps exceptions thrown by controllers to JSON error responses ({@code {"error": "..."}}) with a fitting status.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<?> handleMaxSize(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).body(Map.of(
                "error", "file exceeds maximum allowed size of 5MB"
        ));
    }

    /** Refused account actions (setup, admin links, revoke) carry their own status. */
    @ExceptionHandler(AccountActionException.class)
    public ResponseEntity<?> handleAccountAction(AccountActionException e) {
        return ResponseEntity.status(e.status()).body(Map.of(
                "error", e.getMessage()
        ));
    }

    /** Refused API key requests carry their own status and a stable code for the frontend. */
    @ExceptionHandler(ApiKeyException.class)
    public ResponseEntity<?> handleApiKey(ApiKeyException e) {
        return ResponseEntity.status(e.status()).body(Map.of(
                "error", e.getMessage(),
                "code", e.code()
        ));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<?> handleIllegalState(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "error", e.getMessage()
        ));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "error", e.getMessage()
        ));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<?> handleDataAccessException(DataAccessException e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "error", "Database operation failed: " + e.getMostSpecificCause().getMessage()
        ));
    }
}