package io.github.getimran.ledger.app;

import io.github.getimran.ledger.model.Currency;
import io.github.getimran.ledger.model.Event;
import io.github.getimran.ledger.service.LedgerService;

import java.math.BigDecimal;
import java.util.List;

/** The accounts and event stream from the brief, in the order the brief lists them. */
public final class Scenario {

    private Scenario() {
    }

    public static LedgerService openAccounts() {
        LedgerService ledger = new LedgerService();
        ledger.openAccount("ACC-001", Currency.AED, new BigDecimal("0.00"));
        ledger.openAccount("ACC-002", Currency.BHD, new BigDecimal("0.000"));
        return ledger;
    }

    public static List<Event> events() {
        return List.of(
                new Event.Credit("E1", 1, "ACC-001", new BigDecimal("1200.00"), 1),
                new Event.Debit("E2", 1, "ACC-001", new BigDecimal("950.00"), 1),
                new Event.Authorization("E3", 2, "ACC-001", "Auth-A", new BigDecimal("200.00"), 2),
                new Event.Credit("E4", 3, "ACC-001", new BigDecimal("400.00"), 3),
                new Event.Settlement("E5", 4, "ACC-001", "Auth-A", new BigDecimal("185.00"), 4),
                new Event.Settlement("E6", 4, "ACC-001", "Auth-Z", new BigDecimal("180.00"), 4),
                new Event.Debit("E7", 5, "ACC-001", new BigDecimal("620.00"), 2),
                new Event.Authorization("E8", 5, "ACC-001", "Auth-B", new BigDecimal("90.00"), 5),
                new Event.Reversal("E9", 6, "ACC-001", "E7", 2),
                // Listed last in the brief but booked on Day 5. See AMBIGUITIES.md.
                new Event.InstalmentCredit("E10", 5, "ACC-002", new BigDecimal("10.000"), 3, 5));
    }
}
