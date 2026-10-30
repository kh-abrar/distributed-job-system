package com.system.worker;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.UUID;

/**
 * Base class for worker integration tests: one PostgreSQL 16 container shared by
 * every subclass, migrated by the real Flyway scripts from the common module.
 *
 * Flyway is switched on here only; the worker itself never migrates. The runtime
 * (poll loop, heartbeats, reaper) is off, so tests drive the store and runner
 * directly without a live worker claiming their jobs. End-to-end tests turn it on
 * in their own context.
 */
@SpringBootTest(properties = {"spring.flyway.enabled=true", "worker.enabled=false"})
public abstract class AbstractWorkerIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    static {
        POSTGRES.start();
    }

    @Autowired
    protected JdbcTemplate jdbc;

    /**
     * Before and after every test. After matters because the end-to-end tests start
     * a live worker whose poll loop begins before their first @BeforeEach: leftover
     * PENDING jobs from the previous test class would be claimed, and claims could
     * collide with hand-inserted job_attempts rows (V3's unique constraint).
     */
    @BeforeEach
    @AfterEach
    void cleanDatabase() {
        jdbc.execute("TRUNCATE job_attempts, jobs");
    }

    /** Inserts a PENDING job directly, the way the api would, and returns its id. */
    protected UUID insertPendingJob(String type, String payloadJson) {
        return insertPendingJob(type, payloadJson, 5);
    }

    protected UUID insertPendingJob(String type, String payloadJson, int maxAttempts) {
        return jdbc.queryForObject(
                "INSERT INTO jobs (type, payload, max_attempts) VALUES (?, CAST(? AS jsonb), ?) RETURNING id",
                UUID.class, type, payloadJson, maxAttempts);
    }

    /** Test fixture: makes a job's lease run out without waiting for it. */
    protected void expireLease(UUID jobId) {
        jdbc.update("UPDATE jobs SET lease_expires_at = now() - interval '1 second' WHERE id = ?", jobId);
    }

    protected String statusOf(UUID jobId) {
        return jdbc.queryForObject("SELECT status FROM jobs WHERE id = ?", String.class, jobId);
    }
}
