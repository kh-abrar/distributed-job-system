package com.system.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR-11, FR-12, FR-13: only a PENDING job can be cancelled.
 */
class CancelJobTest extends AbstractIntegrationTest {

    private UUID createPendingJob() {
        HttpResponse<String> created = postJob("{\"type\":\"IO\",\"payload\":{}}");
        assertThat(created.statusCode()).isEqualTo(201);
        return UUID.fromString(json(created).path("id").asString());
    }

    private String statusOf(UUID id) {
        return jdbc.queryForObject("SELECT status FROM jobs WHERE id = ?", String.class, id);
    }

    private Map<String, Object> jobRow(UUID id) {
        return jdbc.queryForMap(
                "SELECT status, attempts, locked_by, lease_expires_at, updated_at FROM jobs WHERE id = ?", id);
    }

    @Test
    void cancelsPendingJob() {
        UUID id = createPendingJob();

        HttpResponse<String> response = cancelJob(id.toString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json(response).path("id").asString()).isEqualTo(id.toString());
        assertThat(json(response).path("status").asString()).isEqualTo("CANCELLED");
        assertThat(statusOf(id)).isEqualTo("CANCELLED");
    }

    /**
     * Decision: the guard is status = 'PENDING' (spec section 4), so a job waiting
     * between retries can be cancelled even though it has run before.
     */
    @Test
    void cancelsPendingJobThatIsWaitingToRetry() {
        UUID id = createPendingJob();
        jdbc.update("UPDATE jobs SET attempts = 2, last_error = 'boom' WHERE id = ?", id);

        HttpResponse<String> response = cancelJob(id.toString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(statusOf(id)).isEqualTo("CANCELLED");
    }

    @Test
    void runningJobCannotBeCancelled() {
        UUID id = createPendingJob();
        // Test fixture: simulate a worker's claim, respecting the V2 CHECKs
        jdbc.update("""
                UPDATE jobs
                SET status = 'RUNNING', attempts = 1, locked_by = 'worker-1',
                    lease_expires_at = now() + interval '30 seconds'
                WHERE id = ?
                """, id);
        Map<String, Object> before = jobRow(id);

        HttpResponse<String> response = cancelJob(id.toString());

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(jobRow(id)).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUCCEEDED", "DEAD", "CANCELLED"})
    void finishedJobCannotBeCancelled(String terminalStatus) {
        UUID id = createPendingJob();
        jdbc.update("""
                UPDATE jobs
                SET status = ?,
                    attempts = CASE WHEN ? = 'CANCELLED' THEN 0 ELSE max_attempts END
                WHERE id = ?
                """, terminalStatus, terminalStatus, id);
        Map<String, Object> before = jobRow(id);

        HttpResponse<String> response = cancelJob(id.toString());

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(jobRow(id)).isEqualTo(before);
    }

    @Test
    void cancellingTwiceReturns409TheSecondTime() {
        UUID id = createPendingJob();
        assertThat(cancelJob(id.toString()).statusCode()).isEqualTo(200);
        Map<String, Object> before = jobRow(id);

        HttpResponse<String> second = cancelJob(id.toString());

        assertThat(second.statusCode()).isEqualTo(409);
        assertThat(jobRow(id)).isEqualTo(before);
    }

    @Test
    void unknownJobReturns404() {
        assertThat(cancelJob(UUID.randomUUID().toString()).statusCode()).isEqualTo(404);
    }

    @Test
    void malformedIdReturns400() {
        assertThat(cancelJob("not-a-uuid").statusCode()).isEqualTo(400);
    }

    /** Only one of many concurrent cancels can win the guarded UPDATE. */
    @Test
    void concurrentCancelsSucceedExactlyOnce() throws Exception {
        int clients = 10;
        UUID id = createPendingJob();
        ExecutorService pool = Executors.newFixedThreadPool(clients);
        CountDownLatch ready = new CountDownLatch(clients);
        CountDownLatch start = new CountDownLatch(1);

        try {
            List<Future<HttpResponse<String>>> futures = new ArrayList<>();
            for (int i = 0; i < clients; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return cancelJob(id.toString());
                }));
            }
            ready.await();
            start.countDown();

            List<Integer> statuses = new ArrayList<>();
            for (Future<HttpResponse<String>> future : futures) {
                statuses.add(future.get().statusCode());
            }

            assertThat(statuses).containsOnly(200, 409);
            assertThat(statuses).filteredOn(s -> s == 200).hasSize(1);
            assertThat(statusOf(id)).isEqualTo("CANCELLED");
        } finally {
            pool.shutdownNow();
        }
    }
}
