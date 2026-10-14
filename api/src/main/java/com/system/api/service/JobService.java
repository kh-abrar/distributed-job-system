package com.system.api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.system.api.dto.CreateJobRequest;
import com.system.api.entity.JobEntity;
import com.system.api.repository.JobRepository;
import com.system.common.enums.JobStatus;
import com.system.api.service.JobCreationResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Optional;

@Service
public class JobService {

    private final JobRepository jobRepository;
    private final ObjectMapper objectMapper;

    // Constructor-based Dependency Injection for repository and Jackson ObjectMapper
    public JobService(
            JobRepository jobRepository,
            ObjectMapper objectMapper) {
        this.jobRepository = jobRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Creates a new job or returns an existing one if an idempotency key is provided
     * and matches a previously processed request.
     *
     * @param request The DTO containing job creation details (type, payload, idempotency key).
     * @return A JobCreationResult containing the job entity and a boolean indicating if it was newly inserted.
     */
    @Transactional
    public JobCreationResult createJob(CreateJobRequest request) {
        // 1. Validate that the job type is present
        if (request.getType() == null) {
            throw new IllegalArgumentException("Job type is required");
        }

        // 2. Validate that the payload is not null or blank
        if (request.getPayload() == null
                || request.getPayload().isBlank()) {
            throw new IllegalArgumentException("Job payload is required");
        }

        // 3. Parse the payload string into a Jackson JsonNode to ensure syntactical correctness
        JsonNode payload;
        try {
            payload = objectMapper.readTree(request.getPayload());
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                    "Job payload must contain valid JSON", exception);
        }

        // 4. Ensure the parsed JSON is specifically a JSON object (not an array, string, number, etc.)
        if (payload == null || !payload.isObject()) {
            throw new IllegalArgumentException(
                    "Job payload must be a JSON object");
        }

        String key = request.getIdempotencyKey();

        // 5. Handle Idempotency if an idempotency key is provided
        if (key != null && !key.isBlank()) {
            key = key.trim();

            // Attempt to insert the record only if the idempotency key does not already exist
            int inserted = jobRepository.insertIfAbsent(
                    key,
                    request.getType().name(),
                    payload.toString());

            // Retrieve the job associated with the idempotency key (either newly inserted or preexisting)
            JobEntity existingJob =
                    jobRepository.findByIdempotencyKey(key)
                            .orElseThrow(() -> new IllegalStateException(
                                    "Job could not be retrieved after insertion"));

            // Verify that the existing job matches the requested type and payload
            boolean sameType =
                    existingJob.getType() == request.getType();

            boolean samePayload =
                    existingJob.getPayload().equals(payload);

            // If the key exists but the request parameters differ, conflict with HTTP 409
            if (!sameType || !samePayload) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Idempotency key is already used with a different request");
            }

            // Return the existing/new job along with a flag (true if newly inserted, false if duplicate)
            return new JobCreationResult(existingJob, inserted == 1);
        }

        // 6. Handle standard (non-idempotent) job creation
        JobEntity job = new JobEntity();
        job.setType(request.getType());
        job.setPayload(payload);
        job.setStatus(JobStatus.PENDING);
        job.setAttempts(0);
        job.setMaxAttempts(5);

        Instant now = Instant.now();
        job.setRunAt(now);
        job.setCreatedAt(now);
        job.setUpdatedAt(now);

        // Save and return the new job entity marked as newly created (true)
        return new JobCreationResult(
                jobRepository.save(job),
                true);
    }
}