package com.system.worker.handler;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.UUID;

/**
 * What a handler gets for one run. (jobId, generation, attempt) is unique per run
 * (ADR-005), so a handler can use it to detect that it is running a job again.
 */
public record JobContext(
        UUID jobId,
        int generation,
        int attempt,
        JsonNode payload) {
}
