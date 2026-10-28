package com.system.worker.runtime;

import com.system.worker.AbstractWorkerIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The whole worker running against PostgreSQL, with short timings so leases,
 * heartbeats and the reaper all come into play within a few seconds.
 *
 * Its own context (the runtime is on), closed after the class so its poll loop
 * can't claim jobs belonging to other test classes.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "worker.enabled=true",
        "worker.id=e2e-worker",
        "worker.threads=2",
        "worker.poll-interval=50ms",
        "worker.lease=2s",
        "worker.heartbeat-interval=300ms",
        "worker.reaper-interval=200ms",
        "worker.backoff.base=100ms",
        "worker.backoff.max=200ms",
        "worker.shutdown-grace=2s"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WorkerRuntimeTest extends AbstractWorkerIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    @Autowired
    private InFlightRuns inFlight;

    /** Let every run finish, so none carries over into the next test. */
    @AfterEach
    void waitForIdleWorker() {
        await().atMost(TIMEOUT).until(() -> inFlight.size() == 0);
    }

    private void awaitStatus(UUID id, String status) {
        await().atMost(TIMEOUT).until(() -> status.equals(statusOf(id)));
    }

    private List<String> outcomes(UUID id) {
        return jdbc.queryForList(
                "SELECT outcome FROM job_attempts WHERE job_id = ? ORDER BY attempt", String.class, id);
    }

    @Test
    void runsAJobToSuccess() {
        UUID id = insertPendingJob("IO", "{\"sleepMs\":50}");

        awaitStatus(id, "SUCCEEDED");

        assertThat(jdbc.queryForObject("SELECT result ->> 'sleptMs' FROM jobs WHERE id = ?", String.class, id))
                .isEqualTo("50");
        assertThat(jdbc.queryForObject("SELECT worker_id FROM job_attempts WHERE job_id = ?", String.class, id))
                .isEqualTo("e2e-worker");
    }

    /** FR-21: failed attempts come back after the backoff until one succeeds. */
    @Test
    void retriesAFlakyJobUntilItSucceeds() {
        UUID id = insertPendingJob("FLAKY", "{\"succeedOnAttempt\":3}");

        awaitStatus(id, "SUCCEEDED");

        assertThat(jdbc.queryForObject("SELECT attempts FROM jobs WHERE id = ?", Integer.class, id)).isEqualTo(3);
        assertThat(outcomes(id)).containsExactly("FAILED", "FAILED", "SUCCEEDED");
    }

    /** G6: a job that always fails stops at max_attempts and is DEAD. */
    @Test
    void jobThatAlwaysFailsEndsDeadAtMaxAttempts() {
        UUID id = insertPendingJob("FLAKY", "{\"failProbability\":1}", 2);

        awaitStatus(id, "DEAD");

        assertThat(jdbc.queryForObject("SELECT attempts FROM jobs WHERE id = ?", Integer.class, id)).isEqualTo(2);
        assertThat(outcomes(id)).containsExactly("FAILED", "FAILED");
    }

    /** FR-17: a job running longer than the lease survives because heartbeats renew it. */
    @Test
    void heartbeatsKeepAJobLongerThanTheLeaseAlive() {
        UUID id = insertPendingJob("IO", "{\"sleepMs\":3500}");

        awaitStatus(id, "SUCCEEDED");

        assertThat(jdbc.queryForObject("SELECT attempts FROM jobs WHERE id = ?", Integer.class, id)).isEqualTo(1);
        assertThat(outcomes(id)).containsExactly("SUCCEEDED");
    }

    /**
     * G3: a worker claimed a job and died. Once its lease runs out the reaper
     * recovers the job, and this worker runs it as attempt 2.
     */
    @Test
    void recoversAJobAbandonedByADeadWorker() {
        UUID id = jdbc.queryForObject("""
                INSERT INTO jobs (type, payload, status, attempts, locked_by, lease_expires_at)
                VALUES ('IO', '{"sleepMs":0}', 'RUNNING', 1, 'ghost-worker', now() + interval '300 milliseconds')
                RETURNING id
                """, UUID.class);
        jdbc.update("INSERT INTO job_attempts (job_id, generation, attempt, worker_id) VALUES (?, 0, 1, 'ghost-worker')", id);

        awaitStatus(id, "SUCCEEDED");

        assertThat(jdbc.queryForObject("SELECT attempts FROM jobs WHERE id = ?", Integer.class, id)).isEqualTo(2);
        assertThat(jdbc.queryForList(
                "SELECT worker_id || ':' || outcome FROM job_attempts WHERE job_id = ? ORDER BY attempt", String.class, id))
                .containsExactly("ghost-worker:LEASE_EXPIRED", "e2e-worker:SUCCEEDED");
    }

    /**
     * FR-26: when a heartbeat finds the run no longer belongs to this worker, the
     * handler is interrupted at once instead of running on for nothing, and the
     * worker writes nothing to the job.
     */
    @Test
    void lostLeaseInterruptsTheRun() {
        UUID id = insertPendingJob("IO", "{\"sleepMs\":30000}");
        await().atMost(TIMEOUT).until(() -> inFlight.size() == 1);

        // Another worker takes the job over (far-off lease so the reaper leaves it alone)
        jdbc.update("UPDATE jobs SET locked_by = 'thief', lease_expires_at = now() + interval '1 hour' WHERE id = ?", id);

        await().atMost(Duration.ofSeconds(3)).until(() -> inFlight.size() == 0);
        assertThat(jdbc.queryForMap("SELECT status, locked_by, last_error, result FROM jobs WHERE id = ?", id))
                .containsEntry("status", "RUNNING").containsEntry("locked_by", "thief")
                .containsEntry("last_error", null).containsEntry("result", null);
        assertThat(outcomes(id)).containsExactly((String) null);
    }

    /** Claims are sized to free threads, so no more than worker.threads jobs ever run at once. */
    @Test
    void neverRunsMoreJobsThanItHasThreads() throws InterruptedException {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            ids.add(insertPendingJob("IO", "{\"sleepMs\":400}"));
        }

        int maxRunning = 0;
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            int running = jdbc.queryForObject("SELECT count(*) FROM jobs WHERE status = 'RUNNING'", Integer.class);
            maxRunning = Math.max(maxRunning, running);
            if (jdbc.queryForObject("SELECT count(*) FROM jobs WHERE status = 'SUCCEEDED'", Integer.class) == ids.size()) {
                break;
            }
            Thread.sleep(20);
        }

        assertThat(maxRunning).isEqualTo(2);
        for (UUID id : ids) {
            assertThat(statusOf(id)).isEqualTo("SUCCEEDED");
        }
    }
}
