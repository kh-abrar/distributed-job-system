package com.system.worker.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.system.common.enums.JobType;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * CPU-bound work for scaling and throughput experiments (spec 1.4): hashes a
 * seed with SHA-256, feeding each digest back in, `iterations` times.
 *
 * Payload: {"iterations": 1..100000000 (default 100000), "seed": any integer (default 0)}.
 * Result: {"iterations": n, "digest": hex}. The same payload always gives the same
 * digest, so a repeated run produces the same result.
 */
@Component
public class CpuHandler implements JobHandler {

    private static final long DEFAULT_ITERATIONS = 100_000;
    private static final long MAX_ITERATIONS = 100_000_000;
    // How often to check for interruption; checking every iteration costs more than the hash
    private static final int INTERRUPT_CHECK_EVERY = 10_000;

    @Override
    public JobType type() {
        return JobType.CPU;
    }

    @Override
    public JsonNode handle(JobContext context) throws JobFailedException, InterruptedException {
        long iterations = Payloads.integer(context.payload(), "iterations", DEFAULT_ITERATIONS, 1, MAX_ITERATIONS);
        long seed = Payloads.integer(context.payload(), "seed", 0, Long.MIN_VALUE, Long.MAX_VALUE);

        MessageDigest sha256 = sha256();
        byte[] digest = sha256.digest(ByteBuffer.allocate(Long.BYTES).putLong(seed).array());
        for (long i = 1; i < iterations; i++) {
            if (i % INTERRUPT_CHECK_EVERY == 0 && Thread.interrupted()) {
                throw new InterruptedException();
            }
            digest = sha256.digest(digest);
        }

        return JsonNodeFactory.instance.objectNode()
                .put("iterations", iterations)
                .put("digest", HexFormat.of().formatHex(digest));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // Every Java runtime is required to provide SHA-256
            throw new IllegalStateException(e);
        }
    }
}
