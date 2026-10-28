package com.system.worker.store;

import com.system.common.enums.JobStatus;
import com.system.common.enums.JobType;
import com.system.worker.AbstractWorkerIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The worker's SQL, one operation at a time (spec section 4, ADR-005).
 * Leases expire by fixture, never by waiting, so these tests are deterministic.
 */
class JobStoreTest extends AbstractWorkerIntegrationTest {

    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final String WORKER_A = "worker-A";
    private static final String WORKER_B = "worker-B";

    @Autowired
    private JobStore store;

    private ClaimedJob claimOne(String workerId) {
        List<ClaimedJob> claimed = store.claim(workerId, 1, LEASE);
        assertThat(claimed).hasSize(1);
        return claimed.get(0);
    }

    private Map<String, Object> job(UUID id) {
        return jdbc.queryForMap("""
                SELECT status, attempts, generation, locked_by, lease_expires_at, last_error,
                       result IS NOT NULL AS has_result
                FROM jobs WHERE id = ?
                """, id);
    }

    private Map<String, Object> history(UUID id, int generation, int attempt) {
        return jdbc.queryForMap("""
                SELECT worker_id, outcome, error, finished_at, manual_requeue
                FROM job_attempts WHERE job_id = ? AND generation = ? AND attempt = ?
                """, id, generation, attempt);
    }

    private boolean resultEquals(UUID id, String json) {
        return jdbc.queryForObject("SELECT result = CAST(? AS jsonb) FROM jobs WHERE id = ?",
                Boolean.class, json, id);
    }

    private boolean secondsFromNowBetween(UUID id, String column, int low, int high) {
        return jdbc.queryForObject(
                "SELECT " + column + " BETWEEN now() + make_interval(secs => ?) AND now() + make_interval(secs => ?) "
                        + "FROM jobs WHERE id = ?",
                Boolean.class, low, high, id);
    }

    /** The api's requeue statement (api JobTransitionRepository.requeueIfDead). */
    private void requeue(UUID id) {
        int rows = jdbc.update("""
                WITH requeued AS (
                    UPDATE jobs
                    SET status = 'PENDING', attempts = 0, generation = generation + 1,
                        run_at = now(), locked_by = NULL, lease_expires_at = NULL
                    WHERE id = ? AND status = 'DEAD'
                    RETURNING id, generation
                )
                INSERT INTO job_attempts (job_id, generation, attempt, finished_at, outcome, manual_requeue)
                SELECT id, generation, 0, now(), 'MANUAL_REQUEUE', true FROM requeued
                """, id);
        assertThat(rows).isEqualTo(1);
    }

    // ---- claim (FR-15, FR-16) ----

    @Test
    void claimTakesTheLeaseAndOpensAHistoryRow() {
        UUID id = insertPendingJob("IO", "{\"sleepMs\":10}");

        ClaimedJob claimed = claimOne(WORKER_A);

        assertThat(claimed).isEqualTo(new ClaimedJob(id, WORKER_A, 0, 1, JobType.IO, "{\"sleepMs\": 10}"));
        Map<String, Object> row = job(id);
        assertThat(row).containsEntry("status", "RUNNING").containsEntry("attempts", 1)
                .containsEntry("locked_by", WORKER_A);
        assertThat(secondsFromNowBetween(id, "lease_expires_at", 29, 31)).isTrue();
        assertThat(history(id, 0, 1)).containsEntry("worker_id", WORKER_A)
                .containsEntry("outcome", null).containsEntry("finished_at", null);
    }

    @Test
    void claimTakesAtMostTheLimitInRunAtOrder() {
        UUID later = insertPendingJob("IO", "{}");
        UUID earliest = insertPendingJob("IO", "{}");
        UUID middle = insertPendingJob("IO", "{}");
        jdbc.update("UPDATE jobs SET run_at = now() - interval '3 minutes' WHERE id = ?", earliest);
        jdbc.update("UPDATE jobs SET run_at = now() - interval '2 minutes' WHERE id = ?", middle);
        jdbc.update("UPDATE jobs SET run_at = now() - interval '1 minute' WHERE id = ?", later);

        List<ClaimedJob> claimed = store.claim(WORKER_A, 2, LEASE);

        assertThat(claimed).extracting(ClaimedJob::jobId).containsExactlyInAnyOrder(earliest, middle);
        assertThat(statusOf(later)).isEqualTo("PENDING");
    }

    @Test
    void claimSkipsJobsWaitingForTheirBackoff() {
        UUID id = insertPendingJob("IO", "{}");
        jdbc.update("UPDATE jobs SET run_at = now() + interval '1 minute' WHERE id = ?", id);

        assertThat(store.claim(WORKER_A, 10, LEASE)).isEmpty();
    }

    @Test
    void claimSkipsJobsThatAreNotPending() {
        UUID running = insertPendingJob("IO", "{}");
        claimOne(WORKER_A);
        UUID cancelled = insertPendingJob("IO", "{}");
        jdbc.update("UPDATE jobs SET status = 'CANCELLED' WHERE id = ?", cancelled);

        assertThat(store.claim(WORKER_B, 10, LEASE)).isEmpty();
        assertThat(job(running)).containsEntry("locked_by", WORKER_A);
    }

    // ---- heartbeat (FR-17) ----

    @Test
    void heartbeatExtendsTheOwnersLease() {
        UUID id = insertPendingJob("IO", "{}");
        ClaimedJob claimed = claimOne(WORKER_A);
        jdbc.update("UPDATE jobs SET lease_expires_at = now() + interval '1 second' WHERE id = ?", id);

        assertThat(store.heartbeat(claimed, LEASE)).isTrue();
        assertThat(secondsFromNowBetween(id, "lease_expires_at", 29, 31)).isTrue();
    }

    // ---- complete (FR-18, FR-19) ----

    @Test
    void completeStoresTheResultAndClosesTheRun() {
        UUID id = insertPendingJob("IO", "{}");
        ClaimedJob claimed = claimOne(WORKER_A);

        assertThat(store.complete(claimed, "{\"ok\":true}")).isTrue();

        assertThat(job(id)).containsEntry("status", "SUCCEEDED").containsEntry("locked_by", null)
                .containsEntry("lease_expires_at", null);
        assertThat(resultEquals(id, "{\"ok\":true}")).isTrue();
        assertThat(history(id, 0, 1)).containsEntry("outcome", "SUCCEEDED");
        assertThat(history(id, 0, 1).get("finished_at")).isNotNull();
    }

    @Test
    void completingTwiceIsRejectedTheSecondTime() {
        insertPendingJob("IO", "{}");
        ClaimedJob claimed = claimOne(WORKER_A);
        assertThat(store.complete(claimed, "{\"n\":1}")).isTrue();

        assertThat(store.complete(claimed, "{\"n\":2}")).isFalse();
        assertThat(resultEquals(claimed.jobId(), "{\"n\":1}")).isTrue();
    }

    /** Decision 3: the fence checks ownership, not time. Nobody else has the job yet. */
    @Test
    void ownerCanCompleteAfterItsLeaseExpiredIfNotYetReaped() {
        UUID id = insertPendingJob("IO", "{}");
        ClaimedJob claimed = claimOne(WORKER_A);
        expireLease(id);

        assertThat(store.complete(claimed, "{}")).isTrue();
        assertThat(statusOf(id)).isEqualTo("SUCCEEDED");
    }

    // ---- G5: stale workers can't write ----

    /**
     * The main fencing test. A loses its lease, the reaper recovers the job and B
     * claims it. Every write A tries with its old claim must change nothing.
     */
    @Test
    void staleWorkerCannotWriteAfterAnotherWorkerTookOver() {
        UUID id = insertPendingJob("IO", "{}");
        ClaimedJob stale = claimOne(WORKER_A);
        expireLease(id);
        assertThat(store.reapExpired(10)).hasSize(1);
        ClaimedJob current = claimOne(WORKER_B);
        assertThat(current.attempt()).isEqualTo(2);
        Map<String, Object> before = job(id);

        assertThat(store.heartbeat(stale, LEASE)).isFalse();
        assertThat(store.complete(stale, "{\"from\":\"A\"}")).isFalse();
        assertThat(store.fail(stale, "boom from A", Duration.ZERO)).isEqualTo(FailOutcome.LOST);
        assertThat(job(id)).isEqualTo(before);

        assertThat(store.complete(current, "{\"from\":\"B\"}")).isTrue();
        assertThat(resultEquals(id, "{\"from\":\"B\"}")).isTrue();
        assertThat(history(id, 0, 1)).containsEntry("worker_id", WORKER_A).containsEntry("outcome", "LEASE_EXPIRED");
        assertThat(history(id, 0, 2)).containsEntry("worker_id", WORKER_B).containsEntry("outcome", "SUCCEEDED");
    }

    /**
     * ADR-005: the same worker id and attempt number come round again after a
     * requeue. Only the generation tells the old claim from the new one.
     */
    @Test
    void staleClaimFromBeforeARequeueIsRejected() {
        UUID id = insertPendingJob("IO", "{}", 1);
        ClaimedJob beforeRequeue = claimOne(WORKER_A);
        expireLease(id);
        assertThat(store.reapExpired(10)).extracting(ReapedJob::status).containsExactly(JobStatus.DEAD);
        requeue(id);
        ClaimedJob afterRequeue = claimOne(WORKER_A);
        assertThat(afterRequeue.attempt()).isEqualTo(beforeRequeue.attempt());
        assertThat(afterRequeue.generation()).isEqualTo(1);

        assertThat(store.heartbeat(beforeRequeue, LEASE)).isFalse();
        assertThat(store.complete(beforeRequeue, "{\"generation\":0}")).isFalse();
        assertThat(store.fail(beforeRequeue, "old", Duration.ZERO)).isEqualTo(FailOutcome.LOST);

        assertThat(store.complete(afterRequeue, "{\"generation\":1}")).isTrue();
        assertThat(resultEquals(id, "{\"generation\":1}")).isTrue();
    }

    // ---- fail and retry (FR-20 to FR-23, G6) ----

    @Test
    void failWithAttemptsLeftSchedulesARetryAfterTheBackoff() {
        UUID id = insertPendingJob("FLAKY", "{}");
        ClaimedJob claimed = claimOne(WORKER_A);

        FailOutcome outcome = store.fail(claimed, "boom", Duration.ofSeconds(10));

        assertThat(outcome).isEqualTo(FailOutcome.RETRY_SCHEDULED);
        assertThat(job(id)).containsEntry("status", "PENDING").containsEntry("attempts", 1)
                .containsEntry("last_error", "boom").containsEntry("locked_by", null)
                .containsEntry("lease_expires_at", null);
        assertThat(secondsFromNowBetween(id, "run_at", 9, 11)).isTrue();
        assertThat(history(id, 0, 1)).containsEntry("outcome", "FAILED").containsEntry("error", "boom");
        // Not claimable until the backoff has passed
        assertThat(store.claim(WORKER_B, 10, LEASE)).isEmpty();
    }

    /** G6: retries stop exactly at max_attempts; after that the job is DEAD for good. */
    @Test
    void failingEveryAttemptEndsDeadAtMaxAttempts() {
        UUID id = insertPendingJob("FLAKY", "{}", 3);

        assertThat(store.fail(claimOne(WORKER_A), "boom 1", Duration.ZERO)).isEqualTo(FailOutcome.RETRY_SCHEDULED);
        assertThat(store.fail(claimOne(WORKER_A), "boom 2", Duration.ZERO)).isEqualTo(FailOutcome.RETRY_SCHEDULED);
        assertThat(store.fail(claimOne(WORKER_A), "boom 3", Duration.ZERO)).isEqualTo(FailOutcome.DEAD);

        assertThat(job(id)).containsEntry("status", "DEAD").containsEntry("attempts", 3)
                .containsEntry("last_error", "boom 3");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM job_attempts WHERE job_id = ? AND outcome = 'FAILED'", Integer.class, id))
                .isEqualTo(3);
        assertThat(store.claim(WORKER_A, 10, LEASE)).isEmpty();
    }

    @Test
    void longErrorsAreTruncated() {
        UUID id = insertPendingJob("FLAKY", "{}");
        ClaimedJob claimed = claimOne(WORKER_A);

        store.fail(claimed, "x".repeat(10_000), Duration.ZERO);

        assertThat(((String) job(id).get("last_error")).length()).isEqualTo(2000);
    }

    // ---- reaper (FR-24, FR-25, G3) ----

    @Test
    void reaperRecoversExpiredLeaseAndTheJobIsClaimableAtOnce() {
        UUID id = insertPendingJob("IO", "{}");
        claimOne(WORKER_A);
        expireLease(id);

        List<ReapedJob> reaped = store.reapExpired(10);

        assertThat(reaped).containsExactly(new ReapedJob(id, WORKER_A, JobStatus.PENDING));
        assertThat(job(id)).containsEntry("status", "PENDING").containsEntry("attempts", 1)
                .containsEntry("locked_by", null).containsEntry("lease_expires_at", null);
        assertThat((String) job(id).get("last_error")).contains("Lease expired").contains(WORKER_A);
        assertThat(history(id, 0, 1)).containsEntry("outcome", "LEASE_EXPIRED");
        // Decision 1: no backoff after a lost lease, so recovery stays within G3's bound
        assertThat(claimOne(WORKER_B).jobId()).isEqualTo(id);
    }

    @Test
    void reaperLeavesLiveLeasesAlone() {
        UUID id = insertPendingJob("IO", "{}");
        claimOne(WORKER_A);

        assertThat(store.reapExpired(10)).isEmpty();
        assertThat(job(id)).containsEntry("status", "RUNNING").containsEntry("locked_by", WORKER_A);
    }

    @Test
    void reaperMarksJobDeadWhenItWasTheLastAttempt() {
        UUID id = insertPendingJob("IO", "{}", 1);
        claimOne(WORKER_A);
        expireLease(id);

        assertThat(store.reapExpired(10)).extracting(ReapedJob::status).containsExactly(JobStatus.DEAD);
        assertThat(statusOf(id)).isEqualTo("DEAD");
        assertThat(store.claim(WORKER_B, 10, LEASE)).isEmpty();
    }
}
