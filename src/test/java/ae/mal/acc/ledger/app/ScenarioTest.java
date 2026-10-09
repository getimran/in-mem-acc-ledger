package ae.mal.acc.ledger.app;

import ae.mal.acc.ledger.config.LedgerConfig;
import ae.mal.acc.ledger.dto.DayReport;
import ae.mal.acc.ledger.dto.FeeAssessment;
import ae.mal.acc.ledger.dto.Outcome;
import ae.mal.acc.ledger.model.Accrual;
import ae.mal.acc.ledger.model.AuthRecord;
import ae.mal.acc.ledger.model.Event;
import ae.mal.acc.ledger.model.LedgerEntry;
import ae.mal.acc.ledger.model.LedgerEntry.EntryType;
import ae.mal.acc.ledger.service.LedgerService;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The brief's event stream, checked against each acceptance criterion. Criteria that were refused
 * (see REJECTED.md) are tested for the behaviour that replaces them.
 */
class ScenarioTest {

    private static final String AED_ACC = "ACC-001";
    private static final String BHD_ACC = "ACC-002";

    private static BigDecimal aed(String v) {
        return new BigDecimal(v).setScale(2);
    }

    private static BigDecimal bhd(String v) {
        return new BigDecimal(v).setScale(3);
    }

    /** Applies and closes every day before {@code day}, then applies {@code day}'s events without closing it. */
    private static LedgerService openThrough(int day) {
        LedgerService ledger = Scenario.openAccounts();
        List<Event> events = Replay.inBookingOrder(Scenario.events());
        for (int d = LedgerConfig.FIRST_DAY; d <= day; d++) {
            for (Event e : events) {
                if (e.bookedDay() == d) {
                    ledger.apply(e);
                }
            }
            if (d < day) {
                ledger.closeDay();
            }
        }
        return ledger;
    }

    private static LedgerService fullReplay(List<DayReport> reportsOut) {
        LedgerService ledger = Scenario.openAccounts();
        reportsOut.addAll(Replay.run(ledger, Scenario.events(), null));
        return ledger;
    }

    private static List<LedgerEntry> entriesOf(LedgerService ledger, String account, EntryType type) {
        return ledger.entries().stream().filter(x -> x.accountId().equals(account) && x.type() == type).toList();
    }

    // AC1 (accepted): Day 2 closing ledger balance, evaluated at end of Day 5 before any fee, is AED -370.00.
    @Test
    void day2BalanceSeenOnDay5BeforeFeesIsMinus370() {
        LedgerService ledger = openThrough(5);
        assertTrue(entriesOf(ledger, AED_ACC, EntryType.OVERDRAFT_FEE).isEmpty(), "no fee booked yet");
        assertEquals(aed("-370.00"), ledger.ledgerBalance(AED_ACC, 2));
    }

    // AC2 (refused): E7 does not cause "exactly one" fee. Once Day 2 is re-opened, Day 4 and Day 5 go negative too.
    @Test
    void e7CausesThreeBackdatedOverdraftFeesNotOne() {
        List<DayReport> reports = new ArrayList<>();
        LedgerService ledger = fullReplay(reports);

        List<FeeAssessment> day5Fees = reports.get(4).fees();
        assertEquals(List.of(2, 4, 5), day5Fees.stream().map(FeeAssessment::forDay).toList());
        assertEquals(aed("-370.00"), day5Fees.get(0).closingBeforeFee());
        assertEquals(aed("-180.00"), day5Fees.get(1).closingBeforeFee());
        assertEquals(aed("-205.00"), day5Fees.get(2).closingBeforeFee());

        // Without E7 the account never goes negative: no fee was booked before Day 5's close.
        for (int i = 0; i < 4; i++) {
            assertTrue(reports.get(i).fees().isEmpty(), "Day " + (i + 1) + " should have no fee");
        }
        // Never twice for the same account and day.
        List<LedgerEntry> fees = entriesOf(ledger, AED_ACC, EntryType.OVERDRAFT_FEE);
        assertEquals(3, fees.size());
        assertEquals(3, fees.stream().map(LedgerEntry::valueDay).distinct().count());
    }

    // AC3 (accepted): the Day 4 settlement of Auth-A is accepted.
    @Test
    void authASettlementOnDay4IsAccepted() {
        List<DayReport> reports = new ArrayList<>();
        LedgerService ledger = fullReplay(reports);

        Outcome e5 = reports.get(3).outcomes().get(0);
        assertEquals("E5", e5.eventId());
        assertTrue(e5.accepted());
        assertEquals(aed("465.00"), reports.get(3).accounts().get(0).closing());
        assertEquals(aed("465.00"), reports.get(3).accounts().get(0).available(), "hold of 200.00 released");
        AuthRecord authA = ledger.authStates().get(0);
        assertEquals(AuthRecord.Status.SETTLED, authA.status());
        assertEquals(aed("185.00"), authA.settledAmount());
    }

    // AC4 (accepted): a settlement for an authorization not in the ledger is rejected and moves no money.
    @Test
    void settlementForUnknownAuthorizationIsRejectedAndMovesNoFunds() {
        List<DayReport> reports = new ArrayList<>();
        LedgerService ledger = fullReplay(reports);

        Outcome e6 = reports.get(3).outcomes().get(1);
        assertEquals("E6", e6.eventId());
        assertFalse(e6.accepted());
        assertTrue(ledger.entries().stream().noneMatch(x -> x.sourceEventId().equals("E6")));
        assertEquals(1, entriesOf(ledger, AED_ACC, EntryType.SETTLEMENT).size(), "only Auth-A settled");
        assertEquals("E6", reports.get(3).errors().get(0).eventId());
    }

    // AC5 (accepted as a rule, but its premise is false): Auth-B is declined, so it never holds anything.
    @Test
    void authBIsDeclinedSoItsHoldNeverApplies() {
        LedgerService ledger = fullReplay(new ArrayList<>());
        AuthRecord authB = ledger.authStates().get(1);
        assertEquals("Auth-B", authB.authId());
        assertEquals(AuthRecord.Status.DECLINED, authB.status());
        assertEquals(aed("0.00"), ledger.activeHolds(AED_ACC));
    }

    // AC6 (refused): after E9 the balance does not return to pre-E7 values, because the fees stay.
    @Test
    void reversalOfE7LeavesFeesInPlace() {
        List<DayReport> reports = new ArrayList<>();
        LedgerService ledger = fullReplay(reports);

        BigDecimal day6BeforeInterest = ledger.ledgerBalance(AED_ACC, 6, x -> x.type() != EntryType.INTEREST);
        assertEquals(aed("390.00"), day6BeforeInterest, "465.00 pre-E7 balance less three 25.00 fees");
        assertEquals(3, entriesOf(ledger, AED_ACC, EntryType.OVERDRAFT_FEE).size());
        // The E7 entry itself is still there, offset by a new REVERSAL entry.
        assertEquals(1, ledger.entries().stream().filter(x -> x.sourceEventId().equals("E7")).count());
        assertEquals(aed("620.00"), entriesOf(ledger, AED_ACC, EntryType.REVERSAL).get(0).amount());
        assertEquals(aed("390.93"), reports.get(5).accounts().get(0).closing());
    }

    // AC7 (refused): three instalments of 3.334 would credit 10.002. They are 3.333 + 3.333 + 3.334.
    @Test
    void bhdInstalmentsSumExactlyToTen() {
        LedgerService ledger = fullReplay(new ArrayList<>());
        List<BigDecimal> parts = entriesOf(ledger, BHD_ACC, EntryType.CREDIT).stream().map(LedgerEntry::amount).toList();
        assertEquals(List.of(bhd("3.333"), bhd("3.333"), bhd("3.334")), parts);
        assertEquals(bhd("10.000"), parts.stream().reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    // AC8 (refused): nothing is discarded. The capitalized total equals the sum of rounded daily accruals.
    @Test
    void capitalizedInterestEqualsSumOfRoundedDailyAccruals() {
        LedgerService ledger = fullReplay(new ArrayList<>());
        for (String account : List.of(AED_ACC, BHD_ACC)) {
            BigDecimal journal = ledger.accruals().stream().filter(a -> a.accountId().equals(account))
                    .map(Accrual::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
            List<LedgerEntry> caps = entriesOf(ledger, account, EntryType.INTEREST);
            assertEquals(1, caps.size(), "single capitalization credit");
            assertEquals(journal, caps.get(0).amount());
            assertEquals(6, caps.get(0).valueDay());
        }
        // ACC-001 final daily view: 250, 225, 625, 415, 390, 390 -> 0.10+0.09+0.25+0.17+0.16+0.16
        assertEquals(aed("0.93"), entriesOf(ledger, AED_ACC, EntryType.INTEREST).get(0).amount());
        // ACC-002: 10.000 on Days 5 and 6 -> 0.004 + 0.004
        assertEquals(bhd("0.008"), entriesOf(ledger, BHD_ACC, EntryType.INTEREST).get(0).amount());
    }

    // Append-only: each day's ledger is a prefix of the next day's, entry for entry.
    @Test
    void ledgerOnlyEverGrows() {
        LedgerService ledger = Scenario.openAccounts();
        List<Event> events = Replay.inBookingOrder(Scenario.events());
        List<LedgerEntry> previous = List.of();
        for (int d = LedgerConfig.FIRST_DAY; d <= LedgerConfig.LAST_DAY; d++) {
            for (Event e : events) {
                if (e.bookedDay() == d) {
                    ledger.apply(e);
                }
            }
            ledger.closeDay();
            List<LedgerEntry> now = List.copyOf(ledger.entries());
            assertEquals(previous, now.subList(0, previous.size()), "Day " + d + " changed an earlier entry");
            previous = now;
        }
    }
}
