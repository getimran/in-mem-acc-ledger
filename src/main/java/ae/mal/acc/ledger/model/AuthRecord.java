package ae.mal.acc.ledger.model;

import java.math.BigDecimal;

/**
 * One step in an authorization's life. The log of these is append-only; the current state of an
 * authorization is its latest record. {@code settledAmount} is null unless status is SETTLED.
 */
public record AuthRecord(
        String authId,
        String accountId,
        Status status,
        BigDecimal authorizedAmount,
        BigDecimal settledAmount,
        int day,
        String eventId,
        String note) {

    public enum Status {
        APPROVED,
        DECLINED,
        SETTLED
    }

    public boolean holdsFunds() {
        return status == Status.APPROVED;
    }
}
