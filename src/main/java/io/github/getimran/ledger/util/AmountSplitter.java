package io.github.getimran.ledger.util;

import io.github.getimran.ledger.model.Currency;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class AmountSplitter {

    private AmountSplitter() {
    }

    /**
     * Splits {@code total} into {@code n} parts at the currency's precision that sum exactly to
     * {@code total}. Every part is the truncated equal share; the last part also takes the remainder.
     */
    public static List<BigDecimal> split(BigDecimal total, int n, Currency currency) {
        BigDecimal share = total.divide(BigDecimal.valueOf(n), currency.scale(), RoundingMode.DOWN);
        List<BigDecimal> parts = new ArrayList<>(Collections.nCopies(n - 1, share));
        parts.add(total.subtract(share.multiply(BigDecimal.valueOf(n - 1))));
        return parts;
    }
}
