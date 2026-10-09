package io.github.getimran.ledger.dto;

import java.math.BigDecimal;

public record FeeAssessment(String accountId, int forDay, BigDecimal amount, BigDecimal closingBeforeFee) {
}
