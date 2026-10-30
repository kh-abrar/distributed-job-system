package com.system.api;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR-7, FR-8: fetch a job's current state. Lease details stay hidden from normal clients.
 */
class GetJobTest extends AbstractIntegrationTest {

    private UUID createJob(String body, String idempotencyKey) {
        HttpResponse<String> created = postJob(body, idempotencyKey);
        assertThat(created.statusCode()).isEqualTo(201);
        return UUID.fromString(json(created).path("id").asString());
    }

    @Test
    void returnsTheJobAsStored() {
        UUID id = createJob("{\"type\":\"CPU\",\"payload\":{\"iterations\":10},\"maxAttempts\":3}", "get-1");

        HttpResponse<String> response = getJob(id.toString());

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode job = json(response);
        assertThat(job.path("id").asString()).isEqualTo(id.toString());
        assertThat(job.path("idempotencyKey").asString()).isEqualTo("get-1");
        assertThat(job.path("type").asString()).isEqualTo("CPU");
        assertThat(job.path("status").asString()).isEqualTo("PENDING");
        assertThat(job.path("attempts").asInt()).isZero();
        assertThat(job.path("maxAttempts").asInt()).isEqualTo(3);
        assertThat(job.path("generation").asInt()).isZero();
        assertThat(job.path("payload").path("iterations").asInt()).isEqualTo(10);
        assertThat(job.path("result").isNull()).isTrue();
        assertThat(job.path("lastError").isNull()).isTrue();
    }

    /** FR-8: the result and the most recent error are shown once they exist. */
    @Test
    void showsResultAndMostRecentError() {
        UUID id = createJob("{\"type\":\"FLAKY\",\"payload\":{}}", null);
        jdbc.update("""
                UPDATE jobs
                SET status = 'SUCCEEDED', attempts = 2, result = '{"attempt":2}'::jsonb,
                    last_error = 'Planned failure on attempt 1'
                WHERE id = ?
                """, id);

        JsonNode job = json(getJob(id.toString()));

        assertThat(job.path("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(job.path("attempts").asInt()).isEqualTo(2);
        assertThat(job.path("result").path("attempt").asInt()).isEqualTo(2);
        assertThat(job.path("lastError").asString()).isEqualTo("Planned failure on attempt 1");
    }

    /** FR-8: lease details belong on an admin endpoint, not in the normal response. */
    @Test
    void neverExposesLeaseDetails() {
        UUID id = createJob("{\"type\":\"IO\",\"payload\":{}}", null);
        jdbc.update("""
                UPDATE jobs
                SET status = 'RUNNING', attempts = 1, locked_by = 'worker-secret-7',
                    lease_expires_at = now() + interval '30 seconds'
                WHERE id = ?
                """, id);

        HttpResponse<String> response = getJob(id.toString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json(response).has("lockedBy")).isFalse();
        assertThat(json(response).has("leaseExpiresAt")).isFalse();
        assertThat(response.body()).doesNotContain("worker-secret-7");
    }

    /**
     * Timestamps are ISO-8601 strings in UTC, e.g. "2026-10-28T15:01:21.545418Z", not
     * epoch numbers. Pinned because it's part of what clients see, and Jackson's date
     * defaults have changed between major versions.
     */
    @Test
    void timestampsAreIsoStringsInUtc() {
        UUID id = createJob("{\"type\":\"IO\",\"payload\":{}}", null);

        JsonNode job = json(getJob(id.toString()));

        for (String field : new String[] {"createdAt", "updatedAt", "runAt"}) {
            JsonNode value = job.path(field);
            assertThat(value.isString()).as(field + " is a string").isTrue();
            assertThat(value.asString()).as(field).endsWith("Z");
            assertThat(Instant.parse(value.asString())).as(field).isNotNull();
        }
    }

    /** Job state changes all the time, so a cached copy would be wrong. */
    @Test
    void isNeverCached() {
        UUID id = createJob("{\"type\":\"IO\",\"payload\":{}}", null);

        assertThat(getJob(id.toString()).headers().firstValue("Cache-Control")).hasValue("no-store");
    }

    @Test
    void unknownJobReturns404Problem() {
        UUID unknown = UUID.randomUUID();

        HttpResponse<String> response = getJob(unknown.toString());

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("application/problem+json"));
        assertThat(json(response).path("detail").asString()).contains(unknown.toString());
    }

    @Test
    void malformedIdReturns400() {
        assertThat(getJob("not-a-uuid").statusCode()).isEqualTo(400);
    }
}
