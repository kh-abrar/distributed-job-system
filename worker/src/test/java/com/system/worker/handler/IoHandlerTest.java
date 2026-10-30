package com.system.worker.handler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

import static com.system.worker.handler.HandlerTestSupport.context;
import static com.system.worker.handler.HandlerTestSupport.interruptWhileRunning;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IoHandlerTest {

    private final IoHandler handler = new IoHandler();

    @Test
    void sleepsForTheRequestedTime() throws Exception {
        long start = System.nanoTime();

        JsonNode result = handler.handle(context(1, "{\"sleepMs\":150}"));

        assertThat((System.nanoTime() - start) / 1_000_000).isGreaterThanOrEqualTo(150);
        assertThat(result.path("sleptMs").asLong()).isEqualTo(150);
    }

    @Test
    void zeroSleepReturnsAtOnce() throws Exception {
        assertThat(handler.handle(context(1, "{\"sleepMs\":0}")).path("sleptMs").asLong()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"sleepMs\":-1}", "{\"sleepMs\":600001}", "{\"sleepMs\":\"long\"}", "{\"sleepMs\":null}"})
    void rejectsInvalidParameters(String payload) {
        assertThatThrownBy(() -> handler.handle(context(1, payload)))
                .isInstanceOf(JobFailedException.class);
    }

    @Test
    void stopsWhenInterrupted() throws Exception {
        Throwable thrown = interruptWhileRunning(handler, context(1, "{\"sleepMs\":60000}"));

        assertThat(thrown).isInstanceOf(InterruptedException.class);
    }
}
