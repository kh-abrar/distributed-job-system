package com.system.api.repository;

import com.system.common.enums.JobType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Native SQL for job submission. Reads of whole jobs stay on JPA (JobRepository).
 */
@Repository
public class JobSubmissionRepository {

    private final JdbcClient jdbc;

    public JobSubmissionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts a new PENDING job, or does nothing if the idempotency key is taken.
     * A NULL key never conflicts, so this is also the path for jobs without a key.
     * status, attempts, run_at and timestamps come from the column defaults, so
     * every time value uses the database clock.
     *
     * @return the new job's id, or empty if a job with this key already exists
     */
    public Optional<UUID> insertIfAbsent(String idempotencyKey, JobType type, String payloadJson, int maxAttempts) {
        return jdbc.sql("""
                        INSERT INTO jobs (idempotency_key, type, payload, max_attempts)
                        VALUES (:key, :type, CAST(:payload AS jsonb), :maxAttempts)
                        ON CONFLICT (idempotency_key) DO NOTHING
                        RETURNING id
                        """)
                .param("key", idempotencyKey)
                .param("type", type.name())
                .param("payload", payloadJson)
                .param("maxAttempts", maxAttempts)
                .query(UUID.class)
                .optional();
    }

    /**
     * Finds the job holding this key and whether it was created by an equivalent request.
     * Payloads are compared as jsonb, so key order and number formatting don't matter.
     */
    public Optional<ExistingSubmission> findByIdempotencyKey(
            String idempotencyKey, JobType type, String payloadJson, int maxAttempts) {
        return jdbc.sql("""
                        SELECT id,
                               (type = :type
                                AND payload = CAST(:payload AS jsonb)
                                AND max_attempts = :maxAttempts) AS same_request
                        FROM jobs
                        WHERE idempotency_key = :key
                        """)
                .param("key", idempotencyKey)
                .param("type", type.name())
                .param("payload", payloadJson)
                .param("maxAttempts", maxAttempts)
                .query((rs, rowNum) -> new ExistingSubmission(
                        rs.getObject("id", UUID.class),
                        rs.getBoolean("same_request")))
                .optional();
    }

    public record ExistingSubmission(UUID id, boolean sameRequest) {
    }
}
