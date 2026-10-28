package com.system.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.system.api.entity.JobEntity;
import com.system.common.enums.JobStatus;
import com.system.common.enums.JobType;

import java.time.Instant;
import java.util.UUID;

/**
 * A job as clients see it. Lease details (locked_by, lease_expires_at) are left out (FR-8).
 *
 * @param generation how many times the job was manually requeued; (id, generation,
 *                   attempts) identifies a run across requeues (ADR-005)
 */
public record JobResponse(
        UUID id,
        String idempotencyKey,
        JobType type,
        JobStatus status,
        int attempts,
        int maxAttempts,
        int generation,
        JsonNode payload,
        JsonNode result,
        String lastError,
        Instant runAt,
        Instant createdAt,
        Instant updatedAt) {

    public static JobResponse from(JobEntity job) {
        return new JobResponse(
                job.getId(),
                job.getIdempotencyKey(),
                job.getType(),
                job.getStatus(),
                job.getAttempts(),
                job.getMaxAttempts(),
                job.getGeneration(),
                job.getPayload(),
                job.getResult(),
                job.getLastError(),
                job.getRunAt(),
                job.getCreatedAt(),
                job.getUpdatedAt());
    }
}
