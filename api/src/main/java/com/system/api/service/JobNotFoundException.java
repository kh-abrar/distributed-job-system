package com.system.api.service;

import java.util.UUID;

/**
 * No job exists with the requested id. Mapped to 404.
 */
public class JobNotFoundException extends RuntimeException {

    public JobNotFoundException(UUID id) {
        super("Job not found: " + id);
    }
}
