package com.system.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Job submission settings, bound from the "jobs" prefix in application.yml.
 */
@ConfigurationProperties(prefix = "jobs")
public record JobsProperties(int payloadMaxBytes, int defaultMaxAttempts) {

    // Fixed rather than configurable: V2__hardening.sql enforces the same range
    public static final int MIN_ATTEMPTS = 1;
    public static final int MAX_ATTEMPTS = 10;

    public JobsProperties {
        if (payloadMaxBytes <= 0) {
            throw new IllegalArgumentException("jobs.payload-max-bytes must be positive");
        }
        // Fail at startup rather than on every submission
        if (defaultMaxAttempts < MIN_ATTEMPTS || defaultMaxAttempts > MAX_ATTEMPTS) {
            throw new IllegalArgumentException(
                    "jobs.default-max-attempts must be between " + MIN_ATTEMPTS + " and " + MAX_ATTEMPTS);
        }
    }
}
