package io.github.getimran.ledger.model;

import java.math.BigDecimal;

/**
 * An incoming event. {@code bookedDay} is the day the event arrives; {@code valueDay} is the
 * day its money counts from. A value day earlier than the booked day is a backdated entry.
 */
public sealed interface Event {

    String id();

    int bookedDay();

    String accountId();

    int valueDay();

    record Credit(String id, int bookedDay, String accountId, BigDecimal amount, int valueDay)
            implements Event {
    }

    record Debit(String id, int bookedDay, String accountId, BigDecimal amount, int valueDay)
            implements Event {
    }

    /** Places a hold. Moves no money. */
    record Authorization(String id, int bookedDay, String accountId, String authId, BigDecimal amount,
                         int valueDay) implements Event {
    }

    /** Captures funds against an earlier authorization and releases its hold. */
    record Settlement(String id, int bookedDay, String accountId, String authId, BigDecimal amount,
                      int valueDay) implements Event {
    }

    /** Offsets every money entry booked by an earlier event with an equal and opposite entry. */
    record Reversal(String id, int bookedDay, String accountId, String reversedEventId, int valueDay)
            implements Event {
    }

    /** One credit split into {@code instalments} entries that sum exactly to {@code total}. */
    record InstalmentCredit(String id, int bookedDay, String accountId, BigDecimal total, int instalments,
                            int valueDay) implements Event {
    }
}
