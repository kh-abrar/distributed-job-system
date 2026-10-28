package com.system.api.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * State transitions, each a single guarded UPDATE (spec section 4).
 *
 * The guard lives in the WHERE clause, so PostgreSQL decides whether the
 * transition is allowed at the moment it takes the row lock. Callers learn the
 * outcome only from the affected row count, never from an earlier read.
 */
@Repository
public class JobTransitionRepository {

    private final JdbcClient jdbc;

    public JobTransitionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * PENDING -> CANCELLED (FR-11).
     *
     * A concurrent claim or cancel holding the row lock makes this wait; once that
     * commits, PostgreSQL re-checks status = 'PENDING' against the new row version,
     * so at most one of them wins.
     *
     * @return true if this call cancelled the job
     */
    public boolean cancelIfPending(UUID id) {
        int updated = jdbc.sql("""
                        UPDATE jobs
                        SET status = 'CANCELLED'
                        WHERE id = :id
                          AND status = 'PENDING'
                        """)
                .param("id", id)
                .update();
        return updated == 1;
    }

    /**
     * DEAD -> PENDING, by an admin (FR-27, ADR-004, ADR-005).
     *
     * Starts a new generation with a fresh attempt budget and writes the flagged
     * MANUAL_REQUEUE history row. Both happen in one statement, so the state change
     * and its history entry can't be separated. Earlier job_attempts rows are kept.
     * last_error is kept too: it records why the job died.
     *
     * @return true if this call requeued the job
     */
    public boolean requeueIfDead(UUID id) {
        int inserted = jdbc.sql("""
                        WITH requeued AS (
                            UPDATE jobs
                            SET status = 'PENDING',
                                attempts = 0,
                                generation = generation + 1,
                                run_at = now(),
                                locked_by = NULL,
                                lease_expires_at = NULL
                            WHERE id = :id
                              AND status = 'DEAD'
                            RETURNING id, generation
                        )
                        INSERT INTO job_attempts (job_id, generation, attempt, finished_at, outcome, manual_requeue)
                        SELECT id, generation, 0, now(), 'MANUAL_REQUEUE', true
                        FROM requeued
                        """)
                .param("id", id)
                .update();
        return inserted == 1;
    }
}
