package com.system.worker;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V3: (job_id, generation, attempt) identifies one run (ADR-005), so the history
 * can hold at most one row for it.
 */
class JobAttemptsSchemaTest extends AbstractWorkerIntegrationTest {

    private void insertAttempt(UUID jobId, int generation, int attempt, boolean manualRequeue) {
        jdbc.update("""
                INSERT INTO job_attempts (job_id, generation, attempt, outcome, manual_requeue)
                VALUES (?, ?, ?, ?, ?)
                """, jobId, generation, attempt, manualRequeue ? "MANUAL_REQUEUE" : null, manualRequeue);
    }

    @Test
    void rejectsASecondRowForTheSameRun() {
        UUID job = insertPendingJob("IO", "{}");
        insertAttempt(job, 0, 1, false);

        assertThatThrownBy(() -> insertAttempt(job, 0, 1, false))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void allowsTheSameAttemptNumberInDifferentGenerations() {
        UUID job = insertPendingJob("IO", "{}");
        insertAttempt(job, 0, 1, false);
        // A requeue starts generation 1 with its flagged attempt-0 row, then attempt 1 runs again
        insertAttempt(job, 1, 0, true);
        insertAttempt(job, 1, 1, false);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM job_attempts WHERE job_id = ?", Integer.class, job)).isEqualTo(3);
    }

    @Test
    void allowsTheSameRunNumbersForDifferentJobs() {
        UUID first = insertPendingJob("IO", "{}");
        UUID second = insertPendingJob("IO", "{}");

        insertAttempt(first, 0, 1, false);
        insertAttempt(second, 0, 1, false);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_attempts", Integer.class)).isEqualTo(2);
    }
}
