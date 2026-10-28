package com.system.worker.handler;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static com.system.worker.handler.HandlerTestSupport.context;
import static com.system.worker.handler.HandlerTestSupport.interruptWhileRunning;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CpuHandlerTest {

    private final CpuHandler handler = new CpuHandler();

    /** Deterministic: the same input always gives the same digest, so repeated runs are comparable. */
    @Test
    void sameSeedAndIterationsGiveTheSameDigest() throws Exception {
        JsonNode first = handler.handle(context(1, "{\"iterations\":1000,\"seed\":7}"));
        JsonNode second = handler.handle(context(2, "{\"iterations\":1000,\"seed\":7}"));
        JsonNode otherSeed = handler.handle(context(1, "{\"iterations\":1000,\"seed\":8}"));

        assertThat(first.path("iterations").asLong()).isEqualTo(1000);
        assertThat(first.path("digest").asText()).hasSize(64).isEqualTo(second.path("digest").asText());
        assertThat(otherSeed.path("digest").asText()).isNotEqualTo(first.path("digest").asText());
    }

    @Test
    void usesDefaultIterationsWhenOmitted() throws Exception {
        assertThat(handler.handle(context(1, "{}")).path("iterations").asLong()).isEqualTo(100_000);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"iterations\":0}",
            "{\"iterations\":100000001}",
            "{\"iterations\":\"many\"}",
            "{\"iterations\":1.5}",
            "{\"seed\":\"x\"}"})
    void rejectsInvalidParameters(String payload) {
        assertThatThrownBy(() -> handler.handle(context(1, payload)))
                .isInstanceOf(JobFailedException.class);
    }

    @Test
    void stopsWhenInterrupted() throws Exception {
        Throwable thrown = interruptWhileRunning(handler, context(1, "{\"iterations\":100000000}"));

        assertThat(thrown).isInstanceOf(InterruptedException.class);
    }
}
