package com.system.worker.store;

import com.system.common.enums.JobStatus;
import com.system.common.enums.JobType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * All of the worker's SQL (spec section 4, ADR-005).
 *
 * Rules every method follows:
 * - Each operation is one autocommit statement. The job update and its
 *   job_attempts row are combined with CTEs, so they can't be split, and no
 *   transaction or row lock is ever held while a handler runs.
 * - Every write after the claim is fenced on (id, RUNNING, locked_by, attempts,
 *   generation) as captured at claim time. The fence checks ownership, not time:
 *   an owner whose lease ran out may still write until someone else takes over.
 * - Success is read from the rows the jobs UPDATE touched, never from an earlier read.
 * - Times come from PostgreSQL's now(), never the worker's clock (Assumption 3).
 */
@Repository
public class JobStore {

    /** Cap for last_error and job_attempts.error (spec Open Question 4). */
    static final int MAX_ERROR_LENGTH = 2000;

    // Includes WHERE: text blocks strip trailing spaces, so "WHERE " + FENCE would lose the space
    private static final String FENCE = """
            WHERE id = :id
              AND status = 'RUNNING'
              AND locked_by = :worker
              AND attempts = :attempt
              AND generation = :generation
            """;

    private final JdbcClient jdbc;

    public JobStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * PENDING -> RUNNING for up to limit claimable jobs (FR-15, FR-16), oldest run_at first.
     *
     * SKIP LOCKED makes competing workers pass over rows another claim, cancel or
     * reaper has locked, instead of waiting for them. Each claimed run gets its
     * job_attempts row in the same statement.
     *
     * attempts < max_attempts is a safety net: no transition leaves a PENDING job
     * at its limit, but if one existed, claiming it would violate the V2 CHECK and
     * fail the whole batch on every poll.
     */
    public List<ClaimedJob> claim(String workerId, int limit, Duration lease) {
        return jdbc.sql("""
                        WITH next AS (
                            SELECT id
                            FROM jobs
                            WHERE status = 'PENDING'
                              AND run_at <= now()
                              AND attempts < max_attempts
                            ORDER BY run_at
                            LIMIT :limit
                            FOR UPDATE SKIP LOCKED
                        ), claimed AS (
                            UPDATE jobs j
                            SET status = 'RUNNING',
                                attempts = j.attempts + 1,
                                locked_by = :worker,
                                lease_expires_at = now() + make_interval(secs => :leaseSeconds)
                            FROM next
                            WHERE j.id = next.id
                            RETURNING j.id, j.generation, j.attempts, j.type, j.payload::text AS payload
                        ), history AS (
                            INSERT INTO job_attempts (job_id, generation, attempt, worker_id)
                            SELECT id, generation, attempts, :worker
                            FROM claimed
                        )
                        SELECT id, generation, attempts, type, payload
                        FROM claimed
                        """)
                .param("limit", limit)
                .param("worker", workerId)
                .param("leaseSeconds", seconds(lease))
                .query((rs, rowNum) -> new ClaimedJob(
                        rs.getObject("id", UUID.class),
                        workerId,
                        rs.getInt("generation"),
                        rs.getInt("attempts"),
                        JobType.valueOf(rs.getString("type")),
                        rs.getString("payload")))
                .list();
    }

    /**
     * Renews the lease (FR-17). A heartbeat is a fenced write too: once another
     * worker has taken the job over, it changes nothing.
     *
     * @return false if this worker no longer owns the run
     */
    public boolean heartbeat(ClaimedJob job, Duration lease) {
        int updated = jdbc.sql("""
                        UPDATE jobs
                        SET lease_expires_at = now() + make_interval(secs => :leaseSeconds)
                        """ + FENCE)
                .param("leaseSeconds", seconds(lease))
                .params(fence(job))
                .update();
        return updated == 1;
    }

    /**
     * RUNNING -> SUCCEEDED with the result (FR-18, FR-19), closing the run's history row.
     *
     * @return false if this worker no longer owns the run; nothing was written
     */
    public boolean complete(ClaimedJob job, String resultJson) {
        return jdbc.sql("""
                        WITH done AS (
                            UPDATE jobs
                            SET status = 'SUCCEEDED',
                                result = CAST(:result AS jsonb),
                                locked_by = NULL,
                                lease_expires_at = NULL
                        """ + FENCE + """
                            RETURNING id
                        ), history AS (
                            UPDATE job_attempts
                            SET finished_at = now(),
                                outcome = 'SUCCEEDED'
                            WHERE job_id IN (SELECT id FROM done)
                              AND generation = :generation
                              AND attempt = :attempt
                        )
                        SELECT count(*) FROM done
                        """)
                .param("result", resultJson)
                .params(fence(job))
                .query(Integer.class)
                .single() == 1;
    }

    /**
     * Records a failed attempt (FR-20). With attempts left the job goes back to
     * PENDING and becomes claimable after the backoff (FR-21); on the last attempt
     * it goes to DEAD (FR-22, FR-23, G6). The CASE reads the row as it was before
     * this update, so "attempts left" is decided on the claimed attempt.
     */
    public FailOutcome fail(ClaimedJob job, String error, Duration backoff) {
        String truncated = truncate(error);
        Optional<String> newStatus = jdbc.sql("""
                        WITH failed AS (
                            UPDATE jobs
                            SET status = CASE WHEN attempts < max_attempts THEN 'PENDING' ELSE 'DEAD' END,
                                run_at = CASE WHEN attempts < max_attempts
                                              THEN now() + make_interval(secs => :backoffSeconds)
                                              ELSE run_at END,
                                last_error = :error,
                                locked_by = NULL,
                                lease_expires_at = NULL
                        """ + FENCE + """
                            RETURNING id, status
                        ), history AS (
                            UPDATE job_attempts
                            SET finished_at = now(),
                                outcome = 'FAILED',
                                error = :error
                            WHERE job_id IN (SELECT id FROM failed)
                              AND generation = :generation
                              AND attempt = :attempt
                        )
                        SELECT status FROM failed
                        """)
                .param("backoffSeconds", seconds(backoff))
                .param("error", truncated)
                .params(fence(job))
                .query(String.class)
                .optional();

        return newStatus
                .map(status -> JobStatus.valueOf(status) == JobStatus.DEAD ? FailOutcome.DEAD : FailOutcome.RETRY_SCHEDULED)
                .orElse(FailOutcome.LOST);
    }

    /**
     * Recovers up to limit jobs whose lease has expired (FR-24, FR-25, G3).
     *
     * Jobs with attempts left go straight back to PENDING with run_at = now(): no
     * backoff, so recovery stays within lease + reaper interval. Jobs on their last
     * attempt go to DEAD. SKIP LOCKED lets every worker run a reaper at once; a row
     * one reaper has locked is skipped by the others, and a heartbeat that commits
     * first moves the lease forward so the row no longer matches.
     */
    public List<ReapedJob> reapExpired(int limit) {
        return jdbc.sql("""
                        WITH expired AS (
                            SELECT id, locked_by
                            FROM jobs
                            WHERE status = 'RUNNING'
                              AND lease_expires_at < now()
                            ORDER BY lease_expires_at
                            LIMIT :limit
                            FOR UPDATE SKIP LOCKED
                        ), reaped AS (
                            UPDATE jobs j
                            SET status = CASE WHEN j.attempts < j.max_attempts THEN 'PENDING' ELSE 'DEAD' END,
                                run_at = CASE WHEN j.attempts < j.max_attempts THEN now() ELSE j.run_at END,
                                last_error = 'Lease expired (worker ' || expired.locked_by || ')',
                                locked_by = NULL,
                                lease_expires_at = NULL
                            FROM expired
                            WHERE j.id = expired.id
                            RETURNING j.id, j.generation, j.attempts, j.status, expired.locked_by AS previous_worker
                        ), history AS (
                            UPDATE job_attempts a
                            SET finished_at = now(),
                                outcome = 'LEASE_EXPIRED',
                                error = 'Lease expired'
                            FROM reaped r
                            WHERE a.job_id = r.id
                              AND a.generation = r.generation
                              AND a.attempt = r.attempts
                        )
                        SELECT id, previous_worker, status FROM reaped
                        """)
                .param("limit", limit)
                .query((rs, rowNum) -> new ReapedJob(
                        rs.getObject("id", UUID.class),
                        rs.getString("previous_worker"),
                        JobStatus.valueOf(rs.getString("status"))))
                .list();
    }

    private static java.util.Map<String, Object> fence(ClaimedJob job) {
        return java.util.Map.of(
                "id", job.jobId(),
                "worker", job.workerId(),
                "attempt", job.attempt(),
                "generation", job.generation());
    }

    private static double seconds(Duration duration) {
        return duration.toMillis() / 1000.0;
    }

    private static String truncate(String error) {
        if (error == null || error.length() <= MAX_ERROR_LENGTH) {
            return error;
        }
        int end = MAX_ERROR_LENGTH;
        // Don't cut a surrogate pair in half
        if (Character.isHighSurrogate(error.charAt(end - 1))) {
            end--;
        }
        return error.substring(0, end);
    }
}
