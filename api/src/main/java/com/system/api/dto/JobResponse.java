package com.system.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.system.api.entity.JobEntity;
import com.system.common.enums.JobStatus;
import com.system.common.enums.JobType;

import java.time.Instant;
import java.util.UUID;

public record JobResponse(
        UUID id,
        String idempotencyKey,
        JobType type,
        JobStatus status,
        int attempts,
        int maxAttempts,
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
                job.getPayload(),
                job.getResult(),
                job.getLastError(),
                job.getRunAt(),
                job.getCreatedAt(),
                job.getUpdatedAt());
    }
}