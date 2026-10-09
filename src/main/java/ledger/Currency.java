package ledger;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Currencies the ledger accepts. Each one carries its own minor-unit precision. */
public enum Currency {
    AED(2),
    BHD(3);

    /** Rounding for computed amounts (interest). Inputs are never rounded, only validated. */
    public static final RoundingMode ROUNDING = RoundingMode.HALF_EVEN;

    private final int scale;

    Currency(int scale) {
        this.scale = scale;
    }

    public int scale() {
        return scale;
    }

    public BigDecimal zero() {
        return BigDecimal.ZERO.setScale(scale);
    }

    /** Rounds a computed value to this currency's precision. */
    public BigDecimal round(BigDecimal value) {
        return value.setScale(scale, ROUNDING);
    }

    /**
     * Returns the amount at this currency's scale, or null if it cannot be represented
     * without rounding (e.g. AED 1.005). Incoming amounts must already be exact.
     */
    public BigDecimal exact(BigDecimal amount) {
        BigDecimal stripped = amount.stripTrailingZeros();
        if (stripped.scale() > scale) {
            return null;
        }
        return amount.setScale(scale);
    }

    public String format(BigDecimal amount) {
        return name() + " " + amount.setScale(scale).toPlainString();
    }
}
