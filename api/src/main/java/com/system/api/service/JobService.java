
package com.system.api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.system.api.dto.CreateJobRequest;
import com.system.api.entity.JobEntity;
import com.system.api.repository.JobRepository;
import com.system.common.enums.JobStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

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
    public JobEntity createJob(CreateJobRequest request) {
        if (request.getType() == null) {
            throw new IllegalArgumentException(
                    "Job type is required");
        }

        if (request.getPayload() == null
                || request.getPayload().isBlank()) {
            throw new IllegalArgumentException(
                    "Job payload is required");
        }

        JsonNode payload;
        try {
            payload = objectMapper.readTree(request.getPayload());
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                    "Job payload must contain valid JSON",
                    exception);
        }

        if (payload == null || !payload.isObject()) {
            throw new IllegalArgumentException(
                    "Job payload must be a JSON object");
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

        if (request.getIdempotencyKey() != null
                && !request.getIdempotencyKey().isBlank()) {
            job.setIdempotencyKey(
                    request.getIdempotencyKey().trim());
        }

        return jobRepository.save(job);
    }
}
