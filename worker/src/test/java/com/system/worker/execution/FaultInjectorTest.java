package com.system.worker.execution;

import com.system.common.enums.JobType;
import com.system.worker.config.WorkerProperties;
import com.system.worker.store.ClaimedJob;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FaultInjectorTest {

    private static final ClaimedJob JOB =
            new ClaimedJob(UUID.randomUUID(), "worker-A", 0, 1, JobType.IO, "{}");

    private final AtomicInteger crashes = new AtomicInteger();

    private FaultInjector injector(double probability, double draw) {
        return new FaultInjector(probability, () -> draw, crashes::incrementAndGet);
    }

    /** Off must mean off: not even a draw of 0 may crash the worker. */
    @Test
    void neverCrashesWhenOff() {
        FaultInjector off = injector(0.0, 0.0);

        for (int i = 0; i < 1000; i++) {
            off.maybeCrashAfterHandler(JOB);
        }

        assertThat(crashes).hasValue(0);
    }

    @Test
    void crashesWhenTheDrawFallsBelowTheProbability() {
        injector(0.3, 0.29).maybeCrashAfterHandler(JOB);
        assertThat(crashes).hasValue(1);

        injector(0.3, 0.3).maybeCrashAfterHandler(JOB);
        assertThat(crashes).hasValue(1);
    }

    @Test
    void alwaysCrashesAtProbabilityOne() {
        injector(1.0, 0.999).maybeCrashAfterHandler(JOB);

        assertThat(crashes).hasValue(1);
    }

    @Test
    void disabledInjectorNeverCrashes() {
        FaultInjector disabled = FaultInjector.disabled();

        for (int i = 0; i < 1000; i++) {
            disabled.maybeCrashAfterHandler(JOB);
        }
        // disabled() has no crash action to count, so reaching here without halting is the test
    }

    @ParameterizedTest
    @ValueSource(doubles = {-0.1, 1.5})
    void rejectsProbabilitiesOutsideZeroToOne(double probability) {
        assertThatThrownBy(() -> new WorkerProperties.Fault(probability))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("crash-after-handler-probability");
    }
}
