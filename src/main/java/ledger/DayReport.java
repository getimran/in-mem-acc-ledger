package ledger;

import java.math.BigDecimal;
import java.util.List;

/** Everything that happened on one booking day, snapshotted when the day closed. */
public record DayReport(
        int day,
        List<Outcome> outcomes,
        List<AccountSnapshot> accounts,
        List<FeeAssessment> fees,
        List<Accrual> accrualPostings,
        List<LedgerEntry> capitalizations,
        List<AuthRecord> authStates,
        List<LedgerError> errors) {

    /** Result of applying one event. Declined authorizations are accepted events, not errors. */
    public record Outcome(String eventId, boolean accepted, String message) {
    }

    /**
     * {@code valueDayBalances.get(i)} is the closing balance of value day i + 1 as known now; earlier
     * days change when backdated entries arrive.
     */
    public record AccountSnapshot(String accountId, Currency currency, BigDecimal closing, BigDecimal available,
                                  List<BigDecimal> valueDayBalances) {
    }

    public record FeeAssessment(String accountId, int forDay, BigDecimal amount, BigDecimal closingBeforeFee) {
    }

    /** {@code eventId} is null for errors raised by the end-of-day run. */
    public record LedgerError(int day, String eventId, String message) {
    }
}
