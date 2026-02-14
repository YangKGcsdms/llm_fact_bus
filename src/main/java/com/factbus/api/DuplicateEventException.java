package com.factbus.api;

/**
 * Thrown when a client attempts to publish an event with an event_id
 * that already exists in the event store (idempotency protection).
 */
public class DuplicateEventException extends RuntimeException {

    public DuplicateEventException(String eventId) {
        super("event_id already exists: " + eventId);
    }
}
