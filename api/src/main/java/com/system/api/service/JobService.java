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

    public JobService(
            JobRepository jobRepository,
            ObjectMapper objectMapper) {
        this.jobRepository = jobRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public JobCreationResult createJob(CreateJobRequest request) {
        if (request.getType() == null) {
            throw new IllegalArgumentException("Job type is required");
        }

        if (request.getPayload() == null
                || request.getPayload().isBlank()) {
            throw new IllegalArgumentException("Job payload is required");
        }

        JsonNode payload;
        try {
            payload = objectMapper.readTree(request.getPayload());
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                    "Job payload must contain valid JSON", exception);
        }

        if (payload == null || !payload.isObject()) {
            throw new IllegalArgumentException(
                    "Job payload must be a JSON object");
        }

        String key = request.getIdempotencyKey();

        if (key != null && !key.isBlank()) {
            key = key.trim();

            int inserted = jobRepository.insertIfAbsent(
                    key,
                    request.getType().name(),
                    payload.toString());

            JobEntity existingJob =
                    jobRepository.findByIdempotencyKey(key)
                            .orElseThrow(() -> new IllegalStateException(
                                    "Job could not be retrieved after insertion"));

            boolean sameType =
                    existingJob.getType() == request.getType();

            boolean samePayload =
                    existingJob.getPayload().equals(payload);

            if (!sameType || !samePayload) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Idempotency key is already used with a different request");
            }

            return new JobCreationResult(existingJob, inserted == 1);
        }

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

        return new JobCreationResult(
                jobRepository.save(job),
                true);
    }
}