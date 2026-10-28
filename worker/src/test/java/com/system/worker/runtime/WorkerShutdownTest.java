package com.system.worker.runtime;

import com.system.worker.AbstractWorkerIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Graceful shutdown: stop claiming, let running jobs finish within the grace
 * period, then interrupt what is left. A fresh context per test, because each
 * test stops the runtime.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "worker.enabled=true",
        "worker.id=shutdown-worker",
        "worker.threads=2",
        "worker.poll-interval=50ms",
        "worker.lease=2s",
        "worker.heartbeat-interval=300ms",
        "worker.reaper-interval=200ms",
        "worker.backoff.base=100ms",
        "worker.backoff.max=200ms",
        "worker.shutdown-grace=1s"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class WorkerShutdownTest extends AbstractWorkerIntegrationTest {

    @Autowired
    private WorkerRuntime runtime;

    @Autowired
    private InFlightRuns inFlight;

    private void awaitRunning(int runs) {
        await().atMost(Duration.ofSeconds(10)).until(() -> inFlight.size() == runs);
    }

    @Test
    void runningJobFinishesDuringGracefulShutdown() {
        UUID id = insertPendingJob("IO", "{\"sleepMs\":500}");
        awaitRunning(1);

        runtime.stop();

        assertThat(statusOf(id)).isEqualTo("SUCCEEDED");
        assertThat(runtime.isRunning()).isFalse();
    }

    /**
     * A job outliving the grace period is interrupted and left untouched: no
     * attempt is used up, and the reaper recovers it once the lease runs out.
     */
    @Test
    void jobOutlivingTheGracePeriodIsInterruptedAndLeftForTheReaper() {
        UUID id = insertPendingJob("IO", "{\"sleepMs\":30000}");
        awaitRunning(1);
        long start = System.nanoTime();

        runtime.stop();

        long tookMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(tookMs).isBetween(900L, 5_000L);
        assertThat(inFlight.size()).isZero();
        assertThat(jdbc.queryForMap("SELECT status, attempts, last_error FROM jobs WHERE id = ?", id))
                .containsEntry("status", "RUNNING").containsEntry("attempts", 1).containsEntry("last_error", null);
        assertThat(jdbc.queryForObject("SELECT outcome FROM job_attempts WHERE job_id = ?", String.class, id)).isNull();
    }

    @Test
    void stoppedWorkerClaimsNothing() throws InterruptedException {
        runtime.stop();

        UUID id = insertPendingJob("IO", "{\"sleepMs\":0}");
        Thread.sleep(500);

        assertThat(statusOf(id)).isEqualTo("PENDING");
    }
}
