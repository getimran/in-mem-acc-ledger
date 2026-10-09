package ledger;

import java.math.BigDecimal;

/**
 * A line in the interest accrual journal (not the ledger). {@code amount} is the change to the
 * rounded accrual for {@code forDay}; a backdated entry can make it negative. The sum of all lines
 * for a day is that day's rounded accrual, and the sum over the window is what gets capitalized.
 */
public record Accrual(String accountId, int forDay, BigDecimal amount, BigDecimal base, int postedOnDay) {
}
