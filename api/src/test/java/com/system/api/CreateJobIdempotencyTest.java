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
 * G4, FR-4, FR-5, FR-6: idempotent submission through the Idempotency-Key header.
 */
class CreateJobIdempotencyTest extends AbstractIntegrationTest {

    private static final String BODY = "{\"type\":\"IO\",\"payload\":{\"sleepMs\":100}}";

    @Test
    void concurrentSubmissionsWithSameKeyCreateExactlyOneJob() throws Exception {
        int clients = 20;
        String key = "g4-" + UUID.randomUUID();
        ExecutorService pool = Executors.newFixedThreadPool(clients);
        CountDownLatch ready = new CountDownLatch(clients);
        CountDownLatch start = new CountDownLatch(1);

        try {
            List<Future<HttpResponse<String>>> futures = new ArrayList<>();
            for (int i = 0; i < clients; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return postJob(BODY, key);
                }));
            }
            // Release every request at once to maximise the overlap
            ready.await();
            start.countDown();

            List<HttpResponse<String>> responses = new ArrayList<>();
            for (Future<HttpResponse<String>> future : futures) {
                responses.add(future.get());
            }

            assertThat(responses).extracting(HttpResponse::statusCode)
                    .containsOnly(201, 200);
            assertThat(responses).filteredOn(r -> r.statusCode() == 201).hasSize(1);
            assertThat(responses).extracting(r -> json(r).path("id").asString())
                    .containsOnly(json(responses.get(0)).path("id").asString());
            assertThat(jobCount()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void sameKeyWithDifferentPayloadIsRejectedWith409() {
        assertThat(postJob(BODY, "fr6-payload").statusCode()).isEqualTo(201);

        HttpResponse<String> response =
                postJob("{\"type\":\"IO\",\"payload\":{\"sleepMs\":999}}", "fr6-payload");

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(jobCount()).isEqualTo(1);
    }

    @Test
    void sameKeyWithDifferentTypeIsRejectedWith409() {
        assertThat(postJob(BODY, "fr6-type").statusCode()).isEqualTo(201);

        HttpResponse<String> response =
                postJob("{\"type\":\"CPU\",\"payload\":{\"sleepMs\":100}}", "fr6-type");

        assertThat(response.statusCode()).isEqualTo(409);
    }

    @Test
    void sameKeyWithDifferentMaxAttemptsIsRejectedWith409() {
        assertThat(postJob("{\"type\":\"IO\",\"payload\":{},\"maxAttempts\":3}", "fr6-max")
                .statusCode()).isEqualTo(201);

        HttpResponse<String> response =
                postJob("{\"type\":\"IO\",\"payload\":{},\"maxAttempts\":4}", "fr6-max");

        assertThat(response.statusCode()).isEqualTo(409);
    }

    @Test
    void sameKeyWithEquivalentPayloadInDifferentKeyOrderReturnsTheSameJob() {
        HttpResponse<String> first =
                postJob("{\"type\":\"IO\",\"payload\":{\"a\":1,\"b\":2}}", "fr5-order");
        HttpResponse<String> second =
                postJob("{\"type\":\"IO\",\"payload\":{\"b\":2,\"a\":1}}", "fr5-order");

        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(json(second).path("id")).isEqualTo(json(first).path("id"));
    }

    /**
     * Submission must never UPDATE the row. A JPA dirty-check flush did exactly that
     * (an unfenced full-row write); the updated_at trigger fires only on UPDATE.
     */
    @Test
    void submittingAndResubmittingNeverUpdatesTheRow() {
        HttpResponse<String> created = postJob(BODY, "no-update");
        postJob(BODY, "no-update");
        postJob(BODY);

        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM jobs WHERE updated_at <> created_at", Integer.class))
                .isZero();
    }

    @Test
    void blankIdempotencyKeyIsRejectedWith400() {
        HttpResponse<String> response = postJob(BODY, "   ");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(jobCount()).isZero();
    }

    @Test
    void idempotencyKeyOver255CharactersIsRejectedWith400() {
        HttpResponse<String> response = postJob(BODY, "k".repeat(256));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(jobCount()).isZero();
    }

    /**
     * FR-5: a repeat returns the existing job as it stands and never re-runs it.
     * There's no worker yet, so "not re-run" is checked as "the row did not change";
     * updated_at is set by a trigger, so even a no-op UPDATE would show here.
     */
    @ParameterizedTest
    @ValueSource(strings = {"SUCCEEDED", "DEAD", "CANCELLED"})
    void resubmittingKeyOfFinishedJobReturnsItUnchanged(String terminalStatus) {
        String key = "fr5-" + terminalStatus;
        HttpResponse<String> created = postJob(BODY, key);
        assertThat(created.statusCode()).isEqualTo(201);
        UUID id = UUID.fromString(json(created).path("id").asString());

        // Test fixture: move the job straight to a terminal state, respecting the V2 CHECKs
        jdbc.update("""
                UPDATE jobs
                SET status = ?,
                    attempts = CASE WHEN ? = 'CANCELLED' THEN 0 ELSE max_attempts END,
                    result = CASE WHEN ? = 'SUCCEEDED' THEN '{"ok":true}'::jsonb END
                WHERE id = ?
                """, terminalStatus, terminalStatus, terminalStatus, id);
        Map<String, Object> before = jobRow(id);

        HttpResponse<String> repeat = postJob(BODY, key);

        assertThat(repeat.statusCode()).isEqualTo(200);
        assertThat(json(repeat).path("id").asString()).isEqualTo(id.toString());
        assertThat(json(repeat).path("status").asString()).isEqualTo(terminalStatus);
        assertThat(jobRow(id)).isEqualTo(before);
        assertThat(jobCount()).isEqualTo(1);
    }

    private Map<String, Object> jobRow(UUID id) {
        return jdbc.queryForMap(
                "SELECT status, attempts, run_at, updated_at FROM jobs WHERE id = ?", id);
    }
}
