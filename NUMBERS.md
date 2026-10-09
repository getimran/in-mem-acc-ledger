# NUMBERS

This lists every constant in the code, where it lives, and why it has that value. For each one, it also says why half that value (or another nearby value) would be wrong.

## Constants

### `LedgerConfig.OVERDRAFT_FEE` = AED 25.00

- **Source:** the brief.
- **Why 25.00 and not 12.50:** the brief fixes the amount. It is charged in full, not pro-rated, and at most once per account per value day.
- **Scale:** it is stored as `25.00` (scale 2) so it adds to AED balances without rescaling.
- **Defined for AED only.** The brief gives no BHD fee and no FX rate. If a BHD account goes negative, the day close logs an error and charges nothing (AMBIGUITIES §20). Converting 25 AED to roughly 2.5 BHD would mean inventing an exchange rate.

### `LedgerConfig.DAILY_INTEREST_RATE` = `0.0004`

- **Source:** the brief says 0.04% per day, and 0.04 / 100 = **0.0004**.
- **This conversion is where errors creep in:**
  - `0.004` is 10× too high. That's the mistake of dividing by 10 instead of 100.
  - `0.04` is 100× too high. That's using the percentage figure as a fraction.
  - `0.0002`, half the rate, has no basis in the brief.
- **Check:** 250.00 × 0.0004 = 0.10 per day. The Day 1 output shows exactly that.
- **Simple, not compound:** it applies to the closing balance each day. Accruals are not added to the balance until the Day 6 capitalization, so one day's interest never earns interest the next day inside the window.

### `Currency.AED.scale` = 2 and `Currency.BHD.scale` = 3

- **Source:** the brief. These also match ISO 4217: the dirham has 100 fils, and the Bahraini dinar has 1,000 fils.
- **Why it matters:**
  - A BHD amount rounded to 2 decimal places would lose real money. For example, BHD 3.334 would become 3.33.
  - AED at 3 decimal places would allow amounts that cannot exist, such as AED 0.005.

### `Currency.ROUNDING` = `HALF_EVEN`

- **Used only for computed amounts,** which in this ledger means daily interest. Input amounts are never rounded: an amount finer than the currency's scale is rejected (`LedgerServiceTest.amountsFinerThanCurrencyPrecisionAreRejectedNotRounded`). Silently rounding money someone sent you is a bug, not a policy.
- **Why HALF_EVEN and not HALF_UP:**
  - HALF_UP rounds every exact half upward, so over many accruals it systematically overpays interest.
  - HALF_EVEN rounds halves to the even digit, so the bias averages to zero.
- **When it matters:** only on exact halves. Example: 1.25 × 0.0004 = 0.0005 becomes 0.00 under HALF_EVEN and 0.01 under HALF_UP (`interestRoundsHalfEvenPerDay`).
- **Does it change this scenario?** No. None of the scenario's daily products lands on an exact half: 0.10, 0.26, 0.186, 0.004 and so on. The choice is about the rule, not about tuning the result.

### Instalment split (`AmountSplitter`): `RoundingMode.DOWN`, remainder on the last part

- **Method:** each part is `total / n` truncated to the currency's scale. The last part is `total − (n−1) × share`.
- **E10:** 10.000 / 3 = 3.333… truncates to 3.333. The parts are **3.333, 3.333, 3.334**, which sum to exactly 10.000.
- **Why DOWN and not HALF_UP:**
  - HALF_UP happens to give 3.333 here too, but for other totals it can round every part up. Example: 2.00 / 3 = 0.666… rounds to 0.67, and 3 × 0.67 = 2.01. The "remainder" on the last part then becomes negative (0.66).
  - DOWN guarantees the remainder is zero or positive, so no part is ever smaller than the others.
- **Why the remainder goes on the last part and not the first:** both are valid. Putting it last means the ledger's running total never exceeds the true amount part-way through the split.

### `Event.InstalmentCredit.instalments` = 3

- **Source:** the brief ("three equal instalments").
- It is a field on the event, not a constant, so the split logic is tested for n = 1..9 (`AmountSplitterTest.splitAlwaysSumsToTotal`).

### `LedgerConfig.FIRST_DAY` = 1 and `LedgerConfig.LAST_DAY` = 6

- **Source:** the brief ("Day 1 through Day 6").
- `LAST_DAY` is also the capitalization day. The brief says "end of Day 6", not end of window + 1, and not the first day of the next period.

### Opening balance value day = 0

- Opening balances are booked on value day 0, before Day 1, so they count in every day of the window.
- Both brief accounts open at zero, so no opening entry is booked. Writing a 0.00 line would be noise, and it would add a zero-amount CREDIT that a reversal could later target.

### Authorization threshold: available after hold `>= 0`

- **Source:** the brief ("at or above zero"). It is inclusive.
- `authorizationLeavingAvailableExactlyZeroIsApproved` pins the exact-zero case. Using `> 0` would wrongly decline a hold for the whole available balance.

### Value-day bounds: `1 <= valueDay <= bookedDay`

- **Lower bound:** a value day before 1 is outside the window.
- **Upper bound:** a value day after the booked day is future-dated. The brief has none, and allowing them would let money count before it exists.
- Backdating, where the value day is earlier than the booked day, is allowed. E7 and E9 depend on it.

## Numbers the scenario produces

These are computed by the code and checked in `ScenarioTest`. Listed here so they can be checked by hand.

| Quantity | Value | Working |
|---|---|---|
| ACC-001 Day 1 | 250.00 | 1200.00 − 950.00 |
| Auth-A available after hold | 50.00 | 250.00 − 200.00 |
| Day 2 as seen on Day 5, before fees | −370.00 | 250.00 − 620.00 |
| Day 3 after Day 2 fee | 5.00 | −370.00 − 25.00 + 400.00 |
| Day 4 before fee | −180.00 | 5.00 − 185.00 |
| Day 5 before fee | −205.00 | −180.00 − 25.00 |
| Auth-B available after hold | −245.00 | (250 + 400 − 185 − 620) − 90. The fees are not booked yet when E8 arrives. |
| Day 6 before interest | 390.00 | 465.00 − 3 × 25.00. E7 and E9 cancel. |
| ACC-001 interest | 0.93 | 0.10 + 0.09 + 0.25 + 0.17 + 0.16 + 0.16 on final day balances 250, 225, 625, 415, 390, 390 |
| ACC-001 Day 6 closing | 390.93 | |
| ACC-002 interest | 0.008 | 0.004 (Day 5) + 0.004 (Day 6) on 10.000 |
