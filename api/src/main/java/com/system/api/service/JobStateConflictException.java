package com.system.api.service;

/**
 * A transition the job's current state doesn't allow (FR-12, FR-13). Mapped to 409.
 */
public class JobStateConflictException extends RuntimeException {

    public JobStateConflictException(String message) {
        super(message);
    }
}
