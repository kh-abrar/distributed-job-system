package com.system.worker.config;

import com.system.worker.store.RetryBackoff;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.UUID;

@Configuration
@EnableConfigurationProperties(WorkerProperties.class)
public class WorkerConfig {

    /** Static: a BeanPostProcessor must exist before the beans it processes. */
    @Bean
    static WorkerPoolSizer workerPoolSizer(Environment environment) {
        return new WorkerPoolSizer(environment);
    }

    @Bean
    RetryBackoff retryBackoff(WorkerProperties properties) {
        return new RetryBackoff(properties.backoff().base(), properties.backoff().max());
    }

    /**
     * The configured id, or hostname-pid-random. The random part keeps two runs on
     * the same host with a reused pid (containers often start at pid 1) distinct.
     */
    @Bean
    WorkerId workerId(WorkerProperties properties) {
        if (properties.id() != null && !properties.id().isBlank()) {
            return new WorkerId(properties.id());
        }
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            host = "unknown-host";
        }
        String random = UUID.randomUUID().toString().substring(0, 8);
        return new WorkerId(host + "-" + ProcessHandle.current().pid() + "-" + random);
    }
}
