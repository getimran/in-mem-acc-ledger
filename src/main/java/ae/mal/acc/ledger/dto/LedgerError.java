package ae.mal.acc.ledger.dto;

/** {@code eventId} is null for errors raised by the end-of-day run. */
public record LedgerError(int day, String eventId, String message) {
}
