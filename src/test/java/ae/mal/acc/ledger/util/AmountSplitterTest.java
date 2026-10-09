package ae.mal.acc.ledger.util;

import ae.mal.acc.ledger.model.Currency;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AmountSplitterTest {

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    @Test
    void splitAlwaysSumsToTotal() {
        assertEquals(List.of(d("3.333"), d("3.333"), d("3.334")), AmountSplitter.split(d("10.000"), 3, Currency.BHD));
        assertEquals(List.of(d("0.33"), d("0.33"), d("0.34")), AmountSplitter.split(d("1.00"), 3, Currency.AED));
        assertEquals(List.of(d("5.00"), d("5.00")), AmountSplitter.split(d("10.00"), 2, Currency.AED));
        for (int n = 1; n <= 9; n++) {
            BigDecimal sum = AmountSplitter.split(d("10.000"), n, Currency.BHD).stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            assertEquals(d("10.000"), sum, "n=" + n);
        }
    }
}
