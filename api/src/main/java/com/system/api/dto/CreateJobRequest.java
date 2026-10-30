package com.system.api.dto;

import com.system.common.enums.JobType;
import tools.jackson.databind.JsonNode;

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
