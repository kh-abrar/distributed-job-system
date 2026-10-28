package com.system.worker.config;

/**
 * This worker's unique id (FR-14), written to locked_by and job_attempts.worker_id.
 */
public record WorkerId(String value) {

    @Override
    public String toString() {
        return value;
    }
}
