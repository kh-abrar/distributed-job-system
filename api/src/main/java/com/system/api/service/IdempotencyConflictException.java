package com.system.api.service;

/**
 * An idempotency key reused with a different request (FR-6). Mapped to 409.
 */
public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException(String message) {
        super(message);
    }
}
