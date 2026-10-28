package com.system.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.http.HttpResponse;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR-2: invalid submissions are rejected with 400, never 500, and never stored.
 */
class CreateJobValidationTest extends AbstractIntegrationTest {

    // Must match jobs.payload.max-bytes in application.yml
    private static final int MAX_PAYLOAD_BYTES = 65536;

    // {"data":""} is 11 bytes; the rest is filler
    private static String payloadOfBytes(int bytes) {
        return "{\"data\":\"" + "x".repeat(bytes - 11) + "\"}";
    }

    static Stream<Arguments> invalidSubmissions() {
        return Stream.of(
                Arguments.of("body is not valid JSON", "{\"type\":\"IO\",\"payload\":"),
                Arguments.of("trailing garbage after the JSON", "{\"type\":\"IO\",\"payload\":{\"a\":1}} xyz"),
                Arguments.of("unsupported type", "{\"type\":\"FOO\",\"payload\":{}}"),
                Arguments.of("missing type", "{\"payload\":{}}"),
                Arguments.of("missing payload", "{\"type\":\"IO\"}"),
                Arguments.of("null payload", "{\"type\":\"IO\",\"payload\":null}"),
                Arguments.of("payload is an array", "{\"type\":\"IO\",\"payload\":[1,2]}"),
                Arguments.of("payload is a string", "{\"type\":\"IO\",\"payload\":\"hello\"}"),
                Arguments.of("payload over the size limit",
                        "{\"type\":\"IO\",\"payload\":" + payloadOfBytes(MAX_PAYLOAD_BYTES + 1) + "}"),
                Arguments.of("maxAttempts 0", "{\"type\":\"IO\",\"payload\":{},\"maxAttempts\":0}"),
                Arguments.of("maxAttempts 11", "{\"type\":\"IO\",\"payload\":{},\"maxAttempts\":11}"),
                // The key moved to a header; a body field must not be silently ignored
                Arguments.of("idempotencyKey in the body",
                        "{\"type\":\"IO\",\"payload\":{},\"idempotencyKey\":\"k1\"}"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidSubmissions")
    void rejectsInvalidSubmissionWith400(String description, String body) {
        HttpResponse<String> response = postJob(body);

        assertThat(response.statusCode()).as(description).isEqualTo(400);
        assertThat(jobCount()).isZero();
    }

    @Test
    void acceptsPayloadExactlyAtTheSizeLimit() {
        HttpResponse<String> response =
                postJob("{\"type\":\"IO\",\"payload\":" + payloadOfBytes(MAX_PAYLOAD_BYTES) + "}");

        assertThat(response.statusCode()).isEqualTo(201);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 10})
    void acceptsMaxAttemptsAtTheRangeBoundaries(int maxAttempts) {
        HttpResponse<String> response =
                postJob("{\"type\":\"IO\",\"payload\":{},\"maxAttempts\":" + maxAttempts + "}");

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(json(response).path("maxAttempts").asInt()).isEqualTo(maxAttempts);
    }

    @Test
    void appliesDefaultMaxAttemptsWhenOmitted() {
        HttpResponse<String> response = postJob("{\"type\":\"IO\",\"payload\":{}}");

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(json(response).path("maxAttempts").asInt()).isEqualTo(5);
    }
}
