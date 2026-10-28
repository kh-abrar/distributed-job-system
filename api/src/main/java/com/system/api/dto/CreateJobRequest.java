package com.system.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.system.common.enums.JobType;

/**
 * Body of POST /api/jobs. The idempotency key travels in the Idempotency-Key header.
 *
 * @param maxAttempts optional override; null means the configured default
 */
public record CreateJobRequest(
        JobType type,
        JsonNode payload,
        Integer maxAttempts) {
}
