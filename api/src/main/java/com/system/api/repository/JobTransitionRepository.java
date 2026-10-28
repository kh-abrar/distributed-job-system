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
}
