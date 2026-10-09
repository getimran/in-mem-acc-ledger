package ledger;

import java.math.BigDecimal;

/**
 * One immutable line in the ledger. {@code amount} is signed: credits positive, debits negative.
 * {@code sourceEventId} ties the line to the event (or end-of-day run) that produced it.
 */
public record LedgerEntry(
        long seq,
        String accountId,
        EntryType type,
        BigDecimal amount,
        int valueDay,
        int bookedDay,
        String sourceEventId,
        String reference) {

    public enum EntryType {
        CREDIT,
        DEBIT,
        SETTLEMENT,
        REVERSAL,
        OVERDRAFT_FEE,
        INTEREST
    }
}
