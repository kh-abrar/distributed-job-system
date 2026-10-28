package com.system.api.service;

/**
 * A submission that fails validation (FR-2). Mapped to 400.
 */
public class InvalidJobRequestException extends RuntimeException {

    public InvalidJobRequestException(String message) {
        super(message);
    }
}
