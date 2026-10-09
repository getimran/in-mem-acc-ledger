package io.github.getimran.ledger.dto;

/** Result of applying one event. Declined authorizations are accepted events, not errors. */
public record Outcome(String eventId, boolean accepted, String message) {
}
