package ledger;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Rules exercised in isolation, outside the brief's event stream. */
class LedgerTest {

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private static Ledger aedAccount() {
        Ledger ledger = new Ledger();
        ledger.openAccount("A", Currency.AED, d("0.00"));
        return ledger;
    }

    @Test
    void approvedHoldReducesAvailableButNotLedgerBalance() {
        Ledger ledger = aedAccount();
        ledger.apply(new Event.Credit("C", 1, "A", d("100.00"), 1));
        DayReport.Outcome out = ledger.apply(new Event.Authorization("H", 1, "A", "Auth-1", d("90.00"), 1));
        assertTrue(out.message().contains("APPROVED"));
        assertEquals(d("100.00"), ledger.ledgerBalance("A", 1));
        assertEquals(d("10.00"), ledger.availableBalance("A", 1));
    }

    @Test
    void authorizationLeavingAvailableExactlyZeroIsApproved() {
        Ledger ledger = aedAccount();
        ledger.apply(new Event.Credit("C", 1, "A", d("90.00"), 1));
        ledger.apply(new Event.Authorization("H", 1, "A", "Auth-1", d("90.00"), 1));
        assertEquals(AuthRecord.Status.APPROVED, ledger.authStates().get(0).status());
    }

    @Test
    void existingHoldsCountAgainstNewAuthorizations() {
        Ledger ledger = aedAccount();
        ledger.apply(new Event.Credit("C", 1, "A", d("100.00"), 1));
        ledger.apply(new Event.Authorization("H1", 1, "A", "Auth-1", d("60.00"), 1));
        ledger.apply(new Event.Authorization("H2", 1, "A", "Auth-2", d("60.00"), 1));
        assertEquals(AuthRecord.Status.DECLINED, ledger.authStates().get(1).status());
    }

    @Test
    void settlementAboveAuthorizedAmountIsRejected() {
        Ledger ledger = aedAccount();
        ledger.apply(new Event.Credit("C", 1, "A", d("500.00"), 1));
        ledger.apply(new Event.Authorization("H", 1, "A", "Auth-1", d("100.00"), 1));
        assertFalse(ledger.apply(new Event.Settlement("S", 1, "A", "Auth-1", d("100.01"), 1)).accepted());
    }

    @Test
    void authorizationCannotSettleTwice() {
        Ledger ledger = aedAccount();
        ledger.apply(new Event.Credit("C", 1, "A", d("500.00"), 1));
        ledger.apply(new Event.Authorization("H", 1, "A", "Auth-1", d("100.00"), 1));
        assertTrue(ledger.apply(new Event.Settlement("S1", 1, "A", "Auth-1", d("50.00"), 1)).accepted());
        assertFalse(ledger.apply(new Event.Settlement("S2", 1, "A", "Auth-1", d("50.00"), 1)).accepted());
    }

    @Test
    void declinedAuthorizationCannotSettle() {
        Ledger ledger = aedAccount();
        ledger.apply(new Event.Authorization("H", 1, "A", "Auth-1", d("10.00"), 1));
        assertFalse(ledger.apply(new Event.Settlement("S", 1, "A", "Auth-1", d("10.00"), 1)).accepted());
        assertTrue(ledger.entries().isEmpty());
    }

    @Test
    void reversingTwiceOrReversingNothingIsRejected() {
        Ledger ledger = aedAccount();
        ledger.apply(new Event.Debit("D", 1, "A", d("10.00"), 1));
        assertTrue(ledger.apply(new Event.Reversal("R1", 1, "A", "D", 1)).accepted());
        assertFalse(ledger.apply(new Event.Reversal("R2", 1, "A", "D", 1)).accepted());
        assertFalse(ledger.apply(new Event.Reversal("R3", 1, "A", "NOPE", 1)).accepted());
        assertEquals(d("0.00"), ledger.ledgerBalance("A", 1));
    }

    @Test
    void amountsFinerThanCurrencyPrecisionAreRejectedNotRounded() {
        Ledger ledger = aedAccount();
        assertFalse(ledger.apply(new Event.Credit("C", 1, "A", d("1.005"), 1)).accepted());
        assertTrue(ledger.apply(new Event.Credit("C2", 1, "A", d("1.000"), 1)).accepted(), "trailing zeros are fine");
        assertNull(Currency.BHD.exact(d("0.0001")));
    }

    @Test
    void futureDatedEventIsRejected() {
        Ledger ledger = aedAccount();
        assertFalse(ledger.apply(new Event.Credit("C", 1, "A", d("1.00"), 2)).accepted());
    }

    @Test
    void eventForWrongBookingDayIsAProgrammingError() {
        Ledger ledger = aedAccount();
        assertThrows(IllegalStateException.class, () -> ledger.apply(new Event.Credit("C", 2, "A", d("1.00"), 2)));
    }

    @Test
    void splitAlwaysSumsToTotal() {
        assertEquals(List.of(d("3.333"), d("3.333"), d("3.334")), Ledger.split(d("10.000"), 3, Currency.BHD));
        assertEquals(List.of(d("0.33"), d("0.33"), d("0.34")), Ledger.split(d("1.00"), 3, Currency.AED));
        assertEquals(List.of(d("5.00"), d("5.00")), Ledger.split(d("10.00"), 2, Currency.AED));
        for (int n = 1; n <= 9; n++) {
            BigDecimal sum = Ledger.split(d("10.000"), n, Currency.BHD).stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            assertEquals(d("10.000"), sum, "n=" + n);
        }
    }

    @Test
    void feeIsChargedOncePerDayEvenIfDayIsReassessed() {
        Ledger ledger = aedAccount();
        ledger.apply(new Event.Debit("D", 1, "A", d("10.00"), 1));
        ledger.closeDay();
        ledger.closeDay();
        long day1Fees = ledger.entries().stream()
                .filter(x -> x.type() == LedgerEntry.EntryType.OVERDRAFT_FEE && x.valueDay() == 1).count();
        assertEquals(1, day1Fees);
    }

    @Test
    void negativeBhdAccountIsReportedBecauseNoBhdFeeExists() {
        Ledger ledger = new Ledger();
        ledger.openAccount("B", Currency.BHD, d("0.000"));
        ledger.apply(new Event.Debit("D", 1, "B", d("1.000"), 1));
        DayReport report = ledger.closeDay();
        assertTrue(report.fees().isEmpty());
        assertEquals(1, report.errors().size());
        assertNull(report.errors().get(0).eventId());
    }

    @Test
    void noInterestOnNegativeOrZeroBalances() {
        Ledger ledger = aedAccount();
        ledger.apply(new Event.Debit("D", 1, "A", d("100.00"), 1));
        for (int day = 1; day <= Ledger.LAST_DAY; day++) {
            ledger.closeDay();
        }
        assertTrue(ledger.entries().stream().noneMatch(x -> x.type() == LedgerEntry.EntryType.INTEREST));
    }

    @Test
    void interestRoundsHalfEvenPerDay() {
        // 1.25 x 0.0004 = 0.0005 -> 0.00 under HALF_EVEN (0.01 under HALF_UP), every day.
        Ledger ledger = aedAccount();
        ledger.apply(new Event.Credit("C", 1, "A", d("1.25"), 1));
        for (int day = 1; day <= Ledger.LAST_DAY; day++) {
            ledger.closeDay();
        }
        assertEquals(d("1.25"), ledger.ledgerBalance("A", Ledger.LAST_DAY));
    }
}
