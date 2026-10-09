package ae.mal.acc.ledger.app;

import ae.mal.acc.ledger.config.LedgerConfig;
import ae.mal.acc.ledger.dto.AccountSnapshot;
import ae.mal.acc.ledger.dto.DayReport;
import ae.mal.acc.ledger.dto.FeeAssessment;
import ae.mal.acc.ledger.dto.LedgerError;
import ae.mal.acc.ledger.dto.Outcome;
import ae.mal.acc.ledger.model.Accrual;
import ae.mal.acc.ledger.model.AuthRecord;
import ae.mal.acc.ledger.model.Currency;
import ae.mal.acc.ledger.model.Event;
import ae.mal.acc.ledger.model.LedgerEntry;
import ae.mal.acc.ledger.service.LedgerService;

import java.io.PrintStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Replays an event stream through a ledger, one booking day at a time, and prints each day. */
public final class Replay {

    private Replay() {
    }

    public static void main(String[] args) {
        run(Scenario.openAccounts(), Scenario.events(), System.out);
    }

    /**
     * Groups events by booking day, keeping their listed order within a day, then applies and
     * closes Day 1 through the last day. Events listed out of booking-day order are reported.
     */
    public static List<DayReport> run(LedgerService ledger, List<Event> events, PrintStream out) {
        List<Event> ordered = inBookingOrder(events);
        reportReordering(events, out);
        List<DayReport> reports = new ArrayList<>();
        for (int day = LedgerConfig.FIRST_DAY; day <= LedgerConfig.LAST_DAY; day++) {
            for (Event event : ordered) {
                if (event.bookedDay() == day) {
                    ledger.apply(event);
                }
            }
            DayReport report = ledger.closeDay();
            reports.add(report);
            if (out != null) {
                print(report, ledger, out);
            }
        }
        return reports;
    }

    static List<Event> inBookingOrder(List<Event> events) {
        // List.sort is stable, so events on the same day keep their listed order.
        List<Event> ordered = new ArrayList<>(events);
        ordered.sort(Comparator.comparingInt(Event::bookedDay));
        return ordered;
    }

    private static void reportReordering(List<Event> events, PrintStream out) {
        if (out == null) {
            return;
        }
        int latestDaySeen = 0;
        for (Event event : events) {
            if (event.bookedDay() < latestDaySeen) {
                out.printf("NOTE: %s is listed after a Day %d event but is booked on Day %d; replayed on Day %d.%n",
                        event.id(), latestDaySeen, event.bookedDay(), event.bookedDay());
            }
            latestDaySeen = Math.max(latestDaySeen, event.bookedDay());
        }
        out.println();
    }

    static void print(DayReport r, LedgerService ledger, PrintStream out) {
        out.println("==================== DAY " + r.day() + " ====================");

        out.println("Events:");
        if (r.outcomes().isEmpty()) {
            out.println("  (none)");
        }
        for (Outcome o : r.outcomes()) {
            out.printf("  %-4s %-8s %s%n", o.eventId(), o.accepted() ? "OK" : "REJECTED", o.message());
        }

        out.println("Closing ledger balance (value date <= Day " + r.day() + "):");
        for (AccountSnapshot s : r.accounts()) {
            out.printf("  %-8s %s %12s   available %12s%n", s.accountId(), s.currency(),
                    plain(s.closing()), plain(s.available()));
        }
        out.println("  Value-day balances as known today (earlier days change when backdated entries arrive):");
        for (AccountSnapshot s : r.accounts()) {
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < s.valueDayBalances().size(); i++) {
                line.append(i == 0 ? "" : " | ").append("D").append(i + 1).append(' ')
                        .append(plain(s.valueDayBalances().get(i)));
            }
            out.printf("    %-8s %s%n", s.accountId(), line);
        }

        out.println("Fee assessments:");
        if (r.fees().isEmpty()) {
            out.println("  (none)");
        }
        for (FeeAssessment f : r.fees()) {
            Currency c = ledger.account(f.accountId()).currency();
            out.printf("  %-8s overdraft fee %s, value Day %d (closing before fee %s)%s%n", f.accountId(),
                    c.format(f.amount()), f.forDay(), plain(f.closingBeforeFee()),
                    f.forDay() < r.day() ? "  <- backdated assessment" : "");
        }

        out.println("Interest accrual journal (lines appended today):");
        if (r.accrualPostings().isEmpty()) {
            out.println("  (none)");
        }
        for (Accrual a : r.accrualPostings()) {
            String signed = (a.amount().signum() > 0 ? "+" : "") + plain(a.amount());
            out.printf("  %-8s Day %d %8s on base %s%s%n", a.accountId(), a.forDay(), signed, plain(a.base()),
                    a.forDay() < r.day() ? "  <- correction" : "");
        }
        for (LedgerEntry cap : r.capitalizations()) {
            Currency c = ledger.account(cap.accountId()).currency();
            out.printf("  %-8s CAPITALIZED %s (= sum of the rounded daily accruals)%n", cap.accountId(),
                    c.format(cap.amount()));
        }

        out.println("Authorization states:");
        if (r.authStates().isEmpty()) {
            out.println("  (none)");
        }
        for (AuthRecord a : r.authStates()) {
            Currency c = ledger.account(a.accountId()).currency();
            String detail = switch (a.status()) {
                case APPROVED -> "hold " + c.format(a.authorizedAmount()) + " active";
                case DECLINED -> c.format(a.authorizedAmount()) + " not held";
                case SETTLED -> c.format(a.settledAmount()) + " captured of " + c.format(a.authorizedAmount())
                        + ", hold released";
            };
            out.printf("  %-7s %-8s %-9s %s (since Day %d)%n", a.authId(), a.accountId(), a.status(), detail, a.day());
        }

        out.println("Errors:");
        if (r.errors().isEmpty()) {
            out.println("  (none)");
        }
        for (LedgerError e : r.errors()) {
            out.printf("  %-4s %s%n", e.eventId() == null ? "EOD" : e.eventId(), e.message());
        }
        out.println();
    }

    private static String plain(BigDecimal value) {
        return value.toPlainString();
    }
}
