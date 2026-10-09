package io.github.getimran.ledger.config;

import io.github.getimran.ledger.model.Currency;

import java.math.BigDecimal;
import java.util.Map;

/** Business constants from the brief. Each one is justified in NUMBERS.md. */
public final class LedgerConfig {

    private LedgerConfig() {
    }

    /** 0.04% per day, as a fraction. */
    public static final BigDecimal DAILY_INTEREST_RATE = new BigDecimal("0.0004");

    /** Overdraft fee per currency. The brief only defines AED. */
    public static final Map<Currency, BigDecimal> OVERDRAFT_FEE = Map.of(Currency.AED, new BigDecimal("25.00"));

    public static final int FIRST_DAY = 1;
    public static final int LAST_DAY = 6;
}
