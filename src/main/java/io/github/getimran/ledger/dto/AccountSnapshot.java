package io.github.getimran.ledger.dto;

import io.github.getimran.ledger.model.Currency;

import java.math.BigDecimal;
import java.util.List;

/**
 * {@code valueDayBalances.get(i)} is the closing balance of value day i + 1 as known now; earlier
 * days change when backdated entries arrive.
 */
public record AccountSnapshot(String accountId, Currency currency, BigDecimal closing, BigDecimal available,
                              List<BigDecimal> valueDayBalances) {
}
