package com.system.worker.config;

import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

/**
 * Sizes the worker's connection pool to what it can use at once.
 *
 * Every JobStore statement runs on its own and returns its connection at once, so
 * the most a worker needs is one connection per handler thread (complete or fail),
 * plus one each for the poll loop (claim), the heartbeat timer and the reaper
 * timer. HikariCP's default of 10 was more than the default 4 threads need, and
 * made a default PostgreSQL (100 connections) run out at about 9 workers
 * (docs/05-experiments.md).
 *
 * The size follows worker.threads, so adding threads can't leave handlers queueing
 * for connections. An explicit spring.datasource.hikari.maximum-pool-size wins.
 */
class WorkerPoolSizer implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(WorkerPoolSizer.class);

    /** The poll loop, the heartbeat timer and the reaper timer. */
    static final int NON_HANDLER_CONNECTIONS = 3;
    // Matches worker.threads in application.yml, for when it isn't set at all
    private static final int DEFAULT_THREADS = 4;

    private final Environment environment;

    WorkerPoolSizer(Environment environment) {
        this.environment = environment;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof HikariDataSource dataSource) {
            Binder binder = Binder.get(environment);
            // The Binder understands every spelling, e.g. SPRING_DATASOURCE_HIKARI_MAXIMUMPOOLSIZE
            if (binder.bind("spring.datasource.hikari.maximum-pool-size", Integer.class).isBound()) {
                return bean;
            }
            int threads = binder.bind("worker.threads", Integer.class).orElse(DEFAULT_THREADS);
            dataSource.setMaximumPoolSize(threads + NON_HANDLER_CONNECTIONS);
            log.info("Connection pool sized to {} ({} handler threads + {})",
                    threads + NON_HANDLER_CONNECTIONS, threads, NON_HANDLER_CONNECTIONS);
        }
        return bean;
    }
}
