package com.system.worker.handler;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

final class HandlerTestSupport {

    static final ObjectMapper MAPPER = new ObjectMapper();

    private HandlerTestSupport() {
    }

    static JobContext context(int attempt, String payloadJson) {
        return new JobContext(UUID.randomUUID(), 0, attempt, MAPPER.readTree(payloadJson));
    }

    /**
     * Starts the handler on its own thread, interrupts it after 100 ms, and returns
     * what it threw.
     */
    static Throwable interruptWhileRunning(JobHandler handler, JobContext context) throws Exception {
        ExecutorService thread = Executors.newSingleThreadExecutor();
        try {
            Future<JsonNode> run = thread.submit(() -> handler.handle(context));
            Thread.sleep(100);
            thread.shutdownNow();
            try {
                run.get(5, TimeUnit.SECONDS);
                return null;
            } catch (ExecutionException e) {
                return e.getCause();
            }
        } finally {
            thread.shutdownNow();
        }
    }
}
