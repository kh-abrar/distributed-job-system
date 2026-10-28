package com.system.worker.handler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static com.system.worker.handler.HandlerTestSupport.context;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FlakyHandlerTest {

    private static FlakyHandler withRandom(double value) {
        return new FlakyHandler(() -> value);
    }

    /** The deterministic mode tests rely on: fail exactly until a given attempt. */
    @Test
    void failsUntilTheConfiguredAttempt() throws Exception {
        FlakyHandler handler = withRandom(0.0);
        String payload = "{\"succeedOnAttempt\":3}";

        assertThatThrownBy(() -> handler.handle(context(1, payload)))
                .isInstanceOf(JobFailedException.class).hasMessageContaining("attempt 1");
        assertThatThrownBy(() -> handler.handle(context(2, payload)))
                .isInstanceOf(JobFailedException.class);
        assertThat(handler.handle(context(3, payload)).path("attempt").asInt()).isEqualTo(3);
    }

    @Test
    void failsWhenTheRandomDrawIsBelowTheProbability() throws Exception {
        String payload = "{\"failProbability\":0.3}";

        assertThatThrownBy(() -> withRandom(0.29).handle(context(1, payload)))
                .isInstanceOf(JobFailedException.class);
        assertThat(withRandom(0.3).handle(context(1, payload)).path("attempt").asInt()).isEqualTo(1);
    }

    @Test
    void defaultsToHalfTheRunsFailing() throws Exception {
        assertThatThrownBy(() -> withRandom(0.49).handle(context(1, "{}")))
                .isInstanceOf(JobFailedException.class);
        assertThat(withRandom(0.5).handle(context(1, "{}"))).isNotNull();
    }

    /** With both set, the attempt rule applies first, then the probability. */
    @Test
    void combinesBothRules() throws Exception {
        String payload = "{\"succeedOnAttempt\":2,\"failProbability\":0.0}";

        assertThatThrownBy(() -> withRandom(0.9).handle(context(1, payload)))
                .isInstanceOf(JobFailedException.class);
        assertThat(withRandom(0.0).handle(context(2, payload))).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"failProbability\":1.5}",
            "{\"failProbability\":-0.1}",
            "{\"failProbability\":\"often\"}",
            "{\"succeedOnAttempt\":0}",
            "{\"succeedOnAttempt\":2.5}"})
    void rejectsInvalidParameters(String payload) {
        assertThatThrownBy(() -> withRandom(0.99).handle(context(5, payload)))
                .isInstanceOf(JobFailedException.class).hasMessageContaining("Invalid payload");
    }
}
