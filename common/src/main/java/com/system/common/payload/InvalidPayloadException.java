package com.system.common.payload;

/**
 * A job payload parameter of the wrong type or out of range. The api turns it into
 * a 400; a worker handler turns it into a failed attempt.
 */
public class InvalidPayloadException extends RuntimeException {

    public InvalidPayloadException(String message) {
        super(message);
    }
}
