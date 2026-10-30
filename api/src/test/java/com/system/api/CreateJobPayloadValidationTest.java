package com.system.api;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR-2: "an invalid payload" is rejected at submission, including handler
 * parameters of the wrong type or out of range. Before, such a job was accepted,
 * then failed every attempt in the worker and ended DEAD.
 *
 * The rules are the handlers' own (common module, JobPayloads). Fields a handler
 * doesn't read are allowed: the payload may carry other data up to the size limit.
 */
class CreateJobPayloadValidationTest extends AbstractIntegrationTest {

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "{\"type\":\"IO\",\"payload\":{\"sleepMs\":\"long\"}}",
            "{\"type\":\"IO\",\"payload\":{\"sleepMs\":-1}}",
            "{\"type\":\"IO\",\"payload\":{\"sleepMs\":600001}}",
            "{\"type\":\"IO\",\"payload\":{\"sleepMs\":1.5}}",
            "{\"type\":\"IO\",\"payload\":{\"sleepMs\":null}}",
            "{\"type\":\"CPU\",\"payload\":{\"iterations\":0}}",
            "{\"type\":\"CPU\",\"payload\":{\"iterations\":100000001}}",
            "{\"type\":\"CPU\",\"payload\":{\"iterations\":\"many\"}}",
            "{\"type\":\"CPU\",\"payload\":{\"seed\":\"x\"}}",
            "{\"type\":\"FLAKY\",\"payload\":{\"failProbability\":1.5}}",
            "{\"type\":\"FLAKY\",\"payload\":{\"failProbability\":-0.1}}",
            "{\"type\":\"FLAKY\",\"payload\":{\"failProbability\":\"often\"}}",
            "{\"type\":\"FLAKY\",\"payload\":{\"succeedOnAttempt\":0}}",
            "{\"type\":\"FLAKY\",\"payload\":{\"succeedOnAttempt\":2.5}}"})
    void rejectsInvalidHandlerParametersWith400(String body) {
        HttpResponse<String> response = postJob(body);

        assertThat(response.statusCode()).as(body).isEqualTo(400);
        assertThat(json(response).path("detail").asString()).startsWith("Invalid payload:");
        assertThat(jobCount()).isZero();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "{\"type\":\"IO\",\"payload\":{}}",
            "{\"type\":\"IO\",\"payload\":{\"sleepMs\":0}}",
            "{\"type\":\"IO\",\"payload\":{\"sleepMs\":600000}}",
            "{\"type\":\"CPU\",\"payload\":{}}",
            "{\"type\":\"CPU\",\"payload\":{\"iterations\":1,\"seed\":-5}}",
            "{\"type\":\"CPU\",\"payload\":{\"iterations\":100000000}}",
            "{\"type\":\"FLAKY\",\"payload\":{}}",
            "{\"type\":\"FLAKY\",\"payload\":{\"failProbability\":0}}",
            "{\"type\":\"FLAKY\",\"payload\":{\"failProbability\":1,\"succeedOnAttempt\":3}}",
            // Fields the handler doesn't read are allowed
            "{\"type\":\"IO\",\"payload\":{\"sleepMs\":10,\"orderId\":\"A-42\"}}"})
    void acceptsValidHandlerParameters(String body) {
        assertThat(postJob(body).statusCode()).as(body).isEqualTo(201);
    }
}
