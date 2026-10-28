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
 * FR-27 / ADR-004 / ADR-005: an admin can put a DEAD job back in the queue with a
 * fresh attempt budget, in a new generation, with a flagged history entry.
 */
class RequeueJobTest extends AbstractIntegrationTest {

    private UUID createJob() {
        HttpResponse<String> created = postJob("{\"type\":\"FLAKY\",\"payload\":{}}");
        assertThat(created.statusCode()).isEqualTo(201);
        return UUID.fromString(json(created).path("id").asText());
    }

    /**
     * Test fixture: the end state of a job that failed every attempt, with one
     * FAILED history row per attempt in the job's current generation.
     */
    private void makeDead(UUID id) {
        jdbc.update("""
                UPDATE jobs
                SET status = 'DEAD', attempts = max_attempts, last_error = 'boom',
                    run_at = now() - interval '1 hour'
                WHERE id = ?
                """, id);
        jdbc.update("""
                INSERT INTO job_attempts (job_id, generation, attempt, worker_id, finished_at, outcome, error)
                SELECT j.id, j.generation, n, 'worker-1', now(), 'FAILED', 'boom'
                FROM jobs j, generate_series(1, j.max_attempts) AS n
                WHERE j.id = ?
                """, id);
    }

    private UUID createDeadJob() {
        UUID id = createJob();
        makeDead(id);
        return id;
    }

    private Map<String, Object> jobRow(UUID id) {
        return jdbc.queryForMap("""
                SELECT status, attempts, generation, run_at, last_error, updated_at
                FROM jobs WHERE id = ?
                """, id);
    }

    private int historyRows(UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM job_attempts WHERE job_id = ?", Integer.class, id);
    }

    private List<Map<String, Object>> requeueRows(UUID id) {
        return jdbc.queryForList("""
                SELECT generation, attempt, worker_id, outcome, finished_at
                FROM job_attempts
                WHERE job_id = ? AND manual_requeue
                ORDER BY generation
                """, id);
    }

    @Test
    void requeuesDeadJobWithFreshBudgetInANewGeneration() {
        UUID id = createDeadJob();

        HttpResponse<String> response = requeueJob(id.toString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json(response).path("status").asText()).isEqualTo("PENDING");
        assertThat(json(response).path("attempts").asInt()).isZero();
        assertThat(json(response).path("generation").asInt()).isEqualTo(1);

        Map<String, Object> row = jobRow(id);
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("attempts")).isEqualTo(0);
        assertThat(row.get("generation")).isEqualTo(1);
        // last_error is kept: it explains why the job died
        assertThat(row.get("last_error")).isEqualTo("boom");
        // run_at moves to now, so the job is claimable immediately
        assertThat(jdbc.queryForObject("SELECT run_at > now() - interval '1 minute' FROM jobs WHERE id = ?",
                Boolean.class, id)).isTrue();
    }

    @Test
    void writesOneFlaggedHistoryRowAndKeepsEarlierAttempts() {
        UUID id = createDeadJob();
        assertThat(historyRows(id)).isEqualTo(5);

        requeueJob(id.toString());

        assertThat(historyRows(id)).isEqualTo(6);
        List<Map<String, Object>> requeues = requeueRows(id);
        assertThat(requeues).hasSize(1);
        assertThat(requeues.get(0))
                .containsEntry("generation", 1)
                .containsEntry("attempt", 0)
                .containsEntry("worker_id", null)
                .containsEntry("outcome", "MANUAL_REQUEUE");
        assertThat(requeues.get(0).get("finished_at")).isNotNull();
        // The automatic attempts are untouched and still belong to generation 0
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM job_attempts
                WHERE job_id = ? AND generation = 0 AND outcome = 'FAILED' AND NOT manual_requeue
                """, Integer.class, id)).isEqualTo(5);
    }

    @Test
    void eachRequeueStartsAnotherGeneration() {
        UUID id = createDeadJob();
        assertThat(requeueJob(id.toString()).statusCode()).isEqualTo(200);
        makeDead(id);

        HttpResponse<String> second = requeueJob(id.toString());

        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(json(second).path("generation").asInt()).isEqualTo(2);
        assertThat(requeueRows(id)).extracting(r -> r.get("generation")).containsExactly(1, 2);
    }

    @Test
    void requeueingTwiceReturns409TheSecondTime() {
        UUID id = createDeadJob();
        assertThat(requeueJob(id.toString()).statusCode()).isEqualTo(200);
        Map<String, Object> before = jobRow(id);

        HttpResponse<String> second = requeueJob(id.toString());

        assertThat(second.statusCode()).isEqualTo(409);
        assertThat(jobRow(id)).isEqualTo(before);
        assertThat(requeueRows(id)).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "RUNNING", "SUCCEEDED", "CANCELLED"})
    void onlyDeadJobsCanBeRequeued(String status) {
        UUID id = createJob();
        // Test fixture: put the job in the given state, respecting the V2 CHECKs
        jdbc.update("""
                UPDATE jobs
                SET status = ?,
                    attempts = CASE WHEN ? IN ('RUNNING', 'SUCCEEDED') THEN 1 ELSE 0 END,
                    locked_by = CASE WHEN ? = 'RUNNING' THEN 'worker-1' END,
                    lease_expires_at = CASE WHEN ? = 'RUNNING' THEN now() + interval '30 seconds' END
                WHERE id = ?
                """, status, status, status, status, id);
        Map<String, Object> before = jobRow(id);

        HttpResponse<String> response = requeueJob(id.toString());

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(jobRow(id)).isEqualTo(before);
        assertThat(historyRows(id)).isZero();
    }

    @Test
    void unknownJobReturns404() {
        assertThat(requeueJob(UUID.randomUUID().toString()).statusCode()).isEqualTo(404);
    }

    @Test
    void malformedIdReturns400() {
        assertThat(requeueJob("not-a-uuid").statusCode()).isEqualTo(400);
    }

    /** Only one of many concurrent requeues can win; there is one new generation and one history row. */
    @Test
    void concurrentRequeuesSucceedExactlyOnce() throws Exception {
        int clients = 10;
        UUID id = createDeadJob();
        ExecutorService pool = Executors.newFixedThreadPool(clients);
        CountDownLatch ready = new CountDownLatch(clients);
        CountDownLatch start = new CountDownLatch(1);

        try {
            List<Future<HttpResponse<String>>> futures = new ArrayList<>();
            for (int i = 0; i < clients; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return requeueJob(id.toString());
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
            assertThat(jobRow(id).get("generation")).isEqualTo(1);
            assertThat(requeueRows(id)).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
