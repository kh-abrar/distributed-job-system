package com.system.worker.handler;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Reads handler parameters from a payload. A missing field takes its default; a
 * field that is present but wrong (wrong type, out of range, JSON null) is a
 * JobFailedException, so the job records why it failed.
 */
final class Payloads {

    private Payloads() {
    }

    static long integer(JsonNode payload, String field, long defaultValue, long min, long max)
            throws JobFailedException {
        Long value = optionalInteger(payload, field, min, max);
        return value == null ? defaultValue : value;
    }

    /** @return null if the field is absent */
    static Long optionalInteger(JsonNode payload, String field, long min, long max) throws JobFailedException {
        JsonNode node = payload.get(field);
        if (node == null) {
            return null;
        }
        if (!node.isIntegralNumber() || !node.canConvertToLong()) {
            throw invalid(field, "must be an integer");
        }
        long value = node.asLong();
        if (value < min || value > max) {
            throw invalid(field, "must be between " + min + " and " + max);
        }
        return value;
    }

    /** @return null if the field is absent */
    static Double optionalNumber(JsonNode payload, String field, double min, double max) throws JobFailedException {
        JsonNode node = payload.get(field);
        if (node == null) {
            return null;
        }
        if (!node.isNumber()) {
            throw invalid(field, "must be a number");
        }
        double value = node.asDouble();
        if (value < min || value > max) {
            throw invalid(field, "must be between " + min + " and " + max);
        }
        return value;
    }

    private static JobFailedException invalid(String field, String problem) {
        return new JobFailedException("Invalid payload: " + field + " " + problem);
    }
}
