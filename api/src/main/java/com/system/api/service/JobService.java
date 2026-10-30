package com.system.api.service;

import com.system.api.config.JobsProperties;
import com.system.api.dto.CreateJobRequest;
import com.system.api.dto.JobPage;
import com.system.api.dto.JobResponse;
import com.system.api.entity.JobEntity;
import com.system.api.repository.JobQueryRepository;
import com.system.api.repository.JobRepository;
import com.system.api.repository.JobSubmissionRepository;
import com.system.api.repository.JobSubmissionRepository.ExistingSubmission;
import com.system.api.repository.JobTransitionRepository;
import com.system.common.enums.JobStatus;
import com.system.common.enums.JobType;
import com.system.common.payload.InvalidPayloadException;
import com.system.common.payload.JobPayloads;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class JobService {

    // The column is unbounded TEXT; capping keys keeps the unique index entries small
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 255;

    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 200;

    private final JobRepository jobRepository;
    private final JobSubmissionRepository submissionRepository;
    private final JobTransitionRepository transitionRepository;
    private final JobQueryRepository queryRepository;
    private final ObjectMapper objectMapper;
    private final JobsProperties properties;

    // Constructor-based Dependency Injection for repositories, Jackson ObjectMapper and job settings
    public JobService(
            JobRepository jobRepository,
            JobSubmissionRepository submissionRepository,
            JobTransitionRepository transitionRepository,
            JobQueryRepository queryRepository,
            ObjectMapper objectMapper,
            JobsProperties properties) {
        this.jobRepository = jobRepository;
        this.submissionRepository = submissionRepository;
        this.transitionRepository = transitionRepository;
        this.queryRepository = queryRepository;
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
        String payloadJson = validatedPayload(request.type(), request.payload());
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
     * Lists jobs in id order, optionally filtered by status (FR-9, FR-10).
     *
     * Pages are keyed on id, not an offset, so a job changing status between two
     * requests can't make the next page skip or repeat another job. Ids are random
     * UUIDs, so the order is stable but not chronological.
     *
     * @param status null for every status
     * @param cursor nextCursor from the previous page, or null for the first page
     * @param limit  page size, or null for the default
     * @throws InvalidJobRequestException if limit is out of range
     */
    @Transactional(readOnly = true)
    public JobPage listJobs(JobStatus status, UUID cursor, Integer limit) {
        int pageSize = limit == null ? DEFAULT_PAGE_SIZE : limit;
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new InvalidJobRequestException("limit must be between 1 and " + MAX_PAGE_SIZE);
        }

        // One extra row tells us whether another page exists, without a COUNT query
        List<JobEntity> rows = queryRepository.findPage(status, cursor, pageSize + 1);
        boolean hasMore = rows.size() > pageSize;
        List<JobEntity> page = hasMore ? rows.subList(0, pageSize) : rows;

        UUID nextCursor = hasMore ? page.get(page.size() - 1).getId() : null;
        return new JobPage(page.stream().map(JobResponse::from).toList(), nextCursor);
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
     * Puts a DEAD job back in the queue with a fresh attempt budget (FR-27).
     * Same shape as cancelJob: the guarded write decides, the read picks the response.
     *
     * @throws JobNotFoundException      if no job exists with this id
     * @throws JobStateConflictException if the job is not DEAD
     */
    @Transactional
    public JobResponse requeueJob(UUID id) {
        boolean requeued = transitionRepository.requeueIfDead(id);

        JobEntity job = jobRepository.findById(id)
                .orElseThrow(() -> new JobNotFoundException(id));
        if (!requeued) {
            throw new JobStateConflictException(
                    "Job " + id + " is " + job.getStatus() + "; only DEAD jobs can be requeued");
        }
        return JobResponse.from(job);
    }

    /**
     * Checks the payload is a JSON object within the size limit, with valid
     * parameters for its job type, and returns it serialized. The size is measured on
     * the serialized form because that is what gets stored.
     *
     * The parameter rules are the handlers' own (JobPayloads in the common module), so
     * a job accepted here can't fail in the worker for having a bad parameter.
     */
    private String validatedPayload(JobType type, JsonNode payload) {
        // JSON null arrives as a NullNode, which isObject() also rejects
        if (payload == null || !payload.isObject()) {
            throw new InvalidJobRequestException("Job payload must be a JSON object");
        }
        try {
            JobPayloads.validate(type, payload);
        } catch (InvalidPayloadException e) {
            throw new InvalidJobRequestException(e.getMessage());
        }
        // Jackson 3 exceptions are unchecked; serializing a tree we just parsed can't fail
        byte[] bytes = objectMapper.writeValueAsBytes(payload);
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
