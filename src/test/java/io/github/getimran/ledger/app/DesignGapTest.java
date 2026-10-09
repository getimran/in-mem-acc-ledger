package io.github.getimran.ledger.app;

import io.github.getimran.ledger.model.LedgerEntry;
import io.github.getimran.ledger.model.LedgerEntry.EntryType;
import io.github.getimran.ledger.service.LedgerService;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * DELIBERATELY FAILING. Excluded from the default run; run it with {@code mvn test -Pdesign-gaps}.
 *
 * <p>This test states something a customer would reasonably expect and that my design does not do.
 * It is here to make the gap visible, not to be "fixed" by changing the assertion.
 */
@Tag("design-gap")
class DesignGapTest {

    /**
     * Expectation: once every entry is in, no overdraft fee should stand on a day whose closing
     * balance (excluding the fee itself) is not negative.
     *
     * <p>What happens instead: E7 (-620, backdated to Day 2) arrives on Day 5 and makes value Days
     * 2, 4 and 5 negative, so the Day 5 close books three AED 25.00 fees. On Day 6, E9 reverses
     * E7, also with value date Day 2. With E7 and E9 cancelling out, those days end at 250.00,
     * 465.00 and 465.00 before fees. All three fees stand anyway.
     *
     * <p>What it shows:
     * <ul>
     *   <li>Fees are assessed on a <em>provisional</em> view of history. My design books a fee the
     *       first time a day looks negative and, because the ledger is append-only, never takes it
     *       back. The customer pays AED 75.00 for a debit that was later reversed.</li>
     *   <li>The fix is not to delete fees. It needs a business rule the brief does not give: either
     *       (a) assess a day's fee only after that day can no longer be backdated into (a cut-off),
     *       or (b) when a reversal turns a fee day non-negative, append a FEE_REFUND entry. Both
     *       stay append-only. I did not pick one because refund policy is a business decision,
     *       and acceptance criterion 2 ("exactly one fee") and 6 ("fees return to pre-E7") both
     *       point in different directions.</li>
     *   <li>The same gap would hit interest if accruals were posted to the ledger daily. They are
     *       not: the accrual journal self-corrects, which is why interest is right (0.93) while fees
     *       are not.</li>
     * </ul>
     */
    @Test
    void noFeeStandsOnADayThatEndsNonNegative() {
        LedgerService ledger = Scenario.openAccounts();
        Replay.run(ledger, Scenario.events(), null);

        List<Integer> unjustifiedFeeDays = ledger.entries().stream()
                .filter(x -> x.accountId().equals("ACC-001") && x.type() == EntryType.OVERDRAFT_FEE)
                .filter(fee -> ledger.ledgerBalance("ACC-001", fee.valueDay(),
                        x -> x.type() != EntryType.OVERDRAFT_FEE).signum() >= 0)
                .map(LedgerEntry::valueDay)
                .toList();

        // Fails with: expected <[]> but was <[2, 4, 5]>
        assertEquals(List.of(), unjustifiedFeeDays,
                "overdraft fees stand on days that end non-negative once E7 is reversed");
    }
}
