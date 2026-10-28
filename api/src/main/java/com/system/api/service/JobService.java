package com.system.api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.system.api.config.JobsProperties;
import com.system.api.dto.CreateJobRequest;
import com.system.api.dto.JobResponse;
import com.system.api.entity.JobEntity;
import com.system.api.repository.JobRepository;
import com.system.api.repository.JobSubmissionRepository;
import com.system.api.repository.JobSubmissionRepository.ExistingSubmission;
import com.system.api.repository.JobTransitionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

@Service
public class JobService {

    // The column is unbounded TEXT; capping keys keeps the unique index entries small
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 255;

    private final JobRepository jobRepository;
    private final JobSubmissionRepository submissionRepository;
    private final JobTransitionRepository transitionRepository;
    private final ObjectMapper objectMapper;
    private final JobsProperties properties;

    // Constructor-based Dependency Injection for repositories, Jackson ObjectMapper and job settings
    public JobService(
            JobRepository jobRepository,
            JobSubmissionRepository submissionRepository,
            JobTransitionRepository transitionRepository,
            ObjectMapper objectMapper,
            JobsProperties properties) {
        this.jobRepository = jobRepository;
        this.submissionRepository = submissionRepository;
        this.transitionRepository = transitionRepository;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /**
     * Creates a new job, or returns the existing one if the idempotency key was
     * already used by an equivalent request (FR-5).
     *
     * Race safety (G4): the INSERT ... ON CONFLICT DO NOTHING blocks while another
     * transaction holds the same key, then does nothing once that one commits. The
     * follow-up SELECT is a new statement, so under READ COMMITTED it sees that row.
     *
     * @param request        the job body (type, payload, optional maxAttempts)
     * @param idempotencyKey value of the Idempotency-Key header, or null if absent
     * @return the job and whether this call created it
     * @throws InvalidJobRequestException   if the submission fails FR-2 validation
     * @throws IdempotencyConflictException if the key belongs to a different request (FR-6)
     */
    @Transactional
    public JobCreationResult createJob(CreateJobRequest request, String idempotencyKey) {
        // 1. Validate everything before touching the database
        if (request.type() == null) {
            throw new InvalidJobRequestException("Job type is required");
        }
        String payloadJson = validatedPayload(request.payload());
        int maxAttempts = resolvedMaxAttempts(request.maxAttempts());
        String key = validatedKey(idempotencyKey);

        // 2. Insert; with no key, or a new key, this always creates the job
        Optional<UUID> insertedId = submissionRepository.insertIfAbsent(
                key, request.type(), payloadJson, maxAttempts);

        UUID id;
        boolean created;
        if (insertedId.isPresent()) {
            id = insertedId.get();
            created = true;
        } else {
            // 3. The key is taken: return that job only if this is the same request
            ExistingSubmission existing = submissionRepository
                    .findByIdempotencyKey(key, request.type(), payloadJson, maxAttempts)
                    .orElseThrow(() -> new IllegalStateException(
                            "Insert conflicted but no job holds key " + key));
            if (!existing.sameRequest()) {
                throw new IdempotencyConflictException(
                        "Idempotency key is already used with a different request");
            }
            id = existing.id();
            created = false;
        }

        // 4. Read the stored row so the response shows database-assigned values
        JobEntity job = jobRepository.findById(id)
                .orElseThrow(() -> new IllegalStateException("Job " + id + " vanished after insert"));
        return new JobCreationResult(JobResponse.from(job), created);
    }

    /**
     * Returns the current state of a job.
     * Read-only: the transaction is flagged readOnly so Hibernate never
     * dirty-checks or flushes the loaded entity.
     *
     * @throws JobNotFoundException if no job exists with this id
     */
    @Transactional(readOnly = true)
    public JobResponse getJob(UUID id) {
        JobEntity job = jobRepository.findById(id)
                .orElseThrow(() -> new JobNotFoundException(id));

        return JobResponse.from(job);
    }

    /**
     * Cancels a PENDING job (FR-11). Running jobs (FR-12) and finished jobs (FR-13)
     * are rejected.
     *
     * The guarded UPDATE decides; the read afterwards only picks the response.
     * Checking the status first and updating second would let a worker claim the
     * job in between.
     *
     * @throws JobNotFoundException       if no job exists with this id
     * @throws JobStateConflictException  if the job is not PENDING
     */
    @Transactional
    public JobResponse cancelJob(UUID id) {
        boolean cancelled = transitionRepository.cancelIfPending(id);

        // Nothing has loaded this job in the transaction yet, so this read sees the row after the UPDATE
        JobEntity job = jobRepository.findById(id)
                .orElseThrow(() -> new JobNotFoundException(id));
        if (!cancelled) {
            throw new JobStateConflictException(
                    "Job " + id + " is " + job.getStatus() + "; only PENDING jobs can be cancelled");
        }
        return JobResponse.from(job);
    }

    /**
     * Checks the payload is a JSON object within the size limit and returns it serialized.
     * The size is measured on the serialized form because that is what gets stored.
     */
    private String validatedPayload(JsonNode payload) {
        // JSON null arrives as a NullNode, which isObject() also rejects
        if (payload == null || !payload.isObject()) {
            throw new InvalidJobRequestException("Job payload must be a JSON object");
        }
        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize a parsed payload", exception);
        }
        if (bytes.length > properties.payloadMaxBytes()) {
            throw new InvalidJobRequestException(
                    "Job payload is " + bytes.length + " bytes; the limit is " + properties.payloadMaxBytes());
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private int resolvedMaxAttempts(Integer requested) {
        if (requested == null) {
            return properties.defaultMaxAttempts();
        }
        if (requested < JobsProperties.MIN_ATTEMPTS || requested > JobsProperties.MAX_ATTEMPTS) {
            throw new InvalidJobRequestException("maxAttempts must be between "
                    + JobsProperties.MIN_ATTEMPTS + " and " + JobsProperties.MAX_ATTEMPTS);
        }
        return requested;
    }

    /** A missing header means no key; a header that is present but blank is a client bug. */
    private String validatedKey(String idempotencyKey) {
        if (idempotencyKey == null) {
            return null;
        }
        if (idempotencyKey.isBlank()) {
            throw new InvalidJobRequestException("Idempotency-Key header must not be blank");
        }
        if (idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new InvalidJobRequestException(
                    "Idempotency-Key must be at most " + MAX_IDEMPOTENCY_KEY_LENGTH + " characters");
        }
        return idempotencyKey;
    }
}
