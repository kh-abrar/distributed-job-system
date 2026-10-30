package com.system.worker.config;

import com.system.worker.AbstractWorkerIntegrationTest;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A worker's connection pool is sized to what it can use at once: one connection
 * per handler thread, plus the poll loop, the heartbeat and the reaper. HikariCP's
 * default of 10 per worker made a default PostgreSQL (100 connections) run out at
 * about 9 workers (docs/05-experiments.md).
 *
 * Each nested class has its own context (different properties), so each injects
 * its own DataSource; the outer instance's field belongs to the outer context.
 */
class WorkerPoolSizeTest extends AbstractWorkerIntegrationTest {

    @Autowired
    private DataSource dataSource;

    private static int maximumPoolSize(DataSource dataSource) {
        return ((HikariDataSource) dataSource).getMaximumPoolSize();
    }

    @Test
    void defaultPoolFitsTheDefaultFourThreads() {
        assertThat(maximumPoolSize(dataSource)).isEqualTo(4 + 3);
    }

    @Nested
    @TestPropertySource(properties = "worker.threads=8")
    class WithMoreThreads {

        @Autowired
        private DataSource nestedDataSource;

        /** A fixed size would starve handler threads as soon as someone adds threads. */
        @Test
        void poolGrowsWithTheThreadCount() {
            assertThat(maximumPoolSize(nestedDataSource)).isEqualTo(8 + 3);
        }
    }

    @Nested
    @TestPropertySource(properties = "spring.datasource.hikari.maximum-pool-size=5")
    class WithAnExplicitPoolSize {

        @Autowired
        private DataSource nestedDataSource;

        @Test
        void explicitSettingWins() {
            assertThat(maximumPoolSize(nestedDataSource)).isEqualTo(5);
        }
    }
}
