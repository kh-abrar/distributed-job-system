package com.system.worker.handler;

import com.system.common.enums.JobType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HandlerRegistryTest {

    @Test
    void findsTheHandlerForEveryType() {
        HandlerRegistry registry = new HandlerRegistry(
                List.of(new CpuHandler(), new IoHandler(), new FlakyHandler()));

        for (JobType type : JobType.values()) {
            assertThat(registry.forType(type).type()).isEqualTo(type);
        }
    }

    /** A job type with no handler would leave its jobs failing on every attempt; refuse to start instead. */
    @Test
    void refusesToStartWithAMissingHandler() {
        assertThatThrownBy(() -> new HandlerRegistry(List.of(new CpuHandler(), new IoHandler())))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("FLAKY");
    }

    @Test
    void refusesToStartWithTwoHandlersForOneType() {
        assertThatThrownBy(() -> new HandlerRegistry(
                List.of(new CpuHandler(), new IoHandler(), new FlakyHandler(), new IoHandler())))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("IO");
    }
}
