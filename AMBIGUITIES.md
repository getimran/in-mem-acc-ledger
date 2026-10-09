# AMBIGUITIES

This lists every place the brief can be read more than one way, the reading chosen, and why. Where a test pins the decision, it is named.

## Event stream and time

### 1. E10 is listed last, but it is booked on Day 5

**Problem:**
- The brief says the stream is "replayed in this order", and lists E10 (Day 5) after E9 (Day 6).
- Taken literally, Day 6 would have to close before E10 is applied. The ledger would then have to accept a Day 5 booking after Day 5 and Day 6 had closed.

**Resolution:**
- Events are grouped by booking day, and the list order is kept within each day (a stable sort).
- E10 is applied on Day 5, after E7 and E8.
- The replay prints a `NOTE` line, so the reordering is visible rather than silent.

**Why:**
- Each event's booking day is explicit data. List position is presentation.
- Treating E10 as arriving on Day 6 would contradict the "Day 5" stamp on the event itself.
- The outcome barely depends on the choice: interest is computed on value days and corrected retroactively, so ACC-002 ends at 10.008 either way. Only the Day 5 report would differ.

### 2. "Booked with value_date equal to the day assessed": which day is "assessed"?

**Problem:** the fee for value Day 2 is only discovered when Day 5 closes. "The day assessed" could mean the day being judged (Day 2) or the day the assessment ran (Day 5).

**Resolution:**
- The fee's `valueDay` is the day being judged (Day 2).
- Its `bookedDay` is the day the assessment ran (Day 5).
- Both are recorded on the entry.

**Why:**
- The rule is evaluated on "that day's closing ledger balance (value_date ≤ that day)". The fee belongs to the same day.
- Dating it Day 5 would leave Day 2 negative with no fee, and would charge Day 5 twice: once for Day 2's overdraft and once for its own.

### 3. Are past days re-evaluated for fees when a backdated entry arrives?

**Resolution:** yes. At every close, value days 1 through today are walked earliest first. Any day that is negative and has no fee yet gets one.

**Why:**
- A backdated debit (E7) changes the closing balance of past days. The fee rule is defined on those closing balances.
- Re-assessing only "today" would ignore Day 2's −370.00, which acceptance criterion 1 explicitly asks us to compute.

### 4. Is Day 2's fee charged at the real end of Day 2?

**Resolution:** no. At the real end of Day 2 the balance was 250.00, so no fee was charged then. The fee is booked at the Day 5 close, when E7 makes Day 2 negative. Each report shows what was known at its own close.

### 5. Does a fee count in the next day's closing balance?

**Resolution:** yes. A fee is a ledger entry with value date Day N, so it is part of every later day's closing balance.

**Consequence:**
- The Day 2 fee turns Day 3 from 30.00 into 5.00.
- Day 4 becomes −180.00 rather than −155.00.
- Day 5 is then −205.00 before its own fee.

Walking the days earliest first (§3) is what makes this ordering hold.

### 6. If a later event makes a fee day non-negative, is the fee refunded?

**Resolution:** no. The ledger is append-only, and the brief has no refund rule.

**Consequence:**
- After E9, value Days 2, 4 and 5 are no longer negative before fees, but all three fees stand.
- This is the deliberately failing test (`DesignGapTest`).

**Possible fixes, both append-only:**
- a fee cut-off, where a day's fee is assessed only once the day can no longer be backdated into;
- a FEE_REFUND entry.

The choice is a business rule, so it is left open rather than guessed.

### 7. When E7 makes Day 2 negative, is Auth-A (approved on Day 2) re-judged?

**Resolution:** no.
- An authorization decision is made once, on the balance known at that moment.
- E3 saw 250.00 and approved it, and it stays approved.
- Re-declining a hold that has already been settled would mean un-settling a real card transaction.

### 8. Which ledger balance does an authorization check use?

**Resolution:**
- It uses entries with value date on or before the authorization's booking day, including entries booked earlier the same day.
- It does not include that day's end-of-day fees, because they don't exist yet.

**Example:**
- E8 arrives after E7, so Auth-B sees 250 + 400 − 185 − 620 = −155.00.
- −155.00 − 90.00 = −245.00, so it is declined.

### 9. Value dates outside the window, or in the future

- A value day before Day 1, or after the event's booking day, is rejected with an error.
- The brief contains neither case. Accepting a future value date would let funds count before they arrive.

## Authorizations and settlements

### 10. "Auth-B is never settled inside the window"

**Problem:** if Auth-B had been approved, how long would its hold last?

**Resolution:**
- Holds have no expiry. An approved, unsettled hold would stay active through Day 6.
- In practice this doesn't arise: Auth-B is declined (§8), so it never holds anything.
- The sentence in the brief reads as a decoy that invites the reader to assume Auth-B was approved.

### 11. Settlement for less than the hold (Auth-A: 185.00 of 200.00)

**Resolution:**
- The settlement is accepted, and the **whole** hold is released.
- The unused 15.00 goes back to available rather than staying held.
- Auth-A's state becomes SETTLED.

**Why:** that is how card captures behave. A partial-capture model with a remaining open hold would need a "final capture" flag that the brief doesn't provide.

### 12. Settlement for more than the hold

**Resolution:** rejected (`settlementAboveAuthorizedAmountIsRejected`).

**Why:** not in the scenario, but the brief gives no over-capture tolerance. Without one, capturing more than was authorized is unauthorized money movement.

### 13. Does a settlement need available balance?

**Resolution:** no. The funds were reserved when the authorization was approved. Checking again would let an unrelated debit in between block a capture that was already approved.

### 14. Settlement against a declined, already-settled, or other-account authorization

**Resolution:** all three are rejected with an error, and no entry is booked. An authorization settles at most once (`authorizationCannotSettleTwice`, `declinedAuthorizationCannotSettle`).

### 15. Unknown authorization (Auth-Z)

**Resolution:**
- The settlement is rejected and logged as an error. No ledger entry is booked.
- No authorization record is created for Auth-Z: it never existed, and inventing a record for it would put an authorization in the log that nobody issued.

### 16. Is a declined authorization an error?

**Resolution:** no.
- It is a normal outcome of a valid event, so it appears under "Authorization states" with the arithmetic that caused it.
- "Errors" is reserved for events that could not be applied at all.

### 17. Are plain debits balance-checked?

**Resolution:** no.
- The brief applies the available-balance rule to authorizations only.
- Overdraft fees exist because debits can overdraw, and E7 is posted even though it drives the account to −370.00 (acceptance criterion 1 assumes it posted).

## Reversals

### 18. What does "reverses E7" book?

**Resolution:**
- A new REVERSAL entry of +620.00. E7's own entry is untouched.
- The value date is taken from E9 (Day 2), which matches E7's.
- Reversing a reversal, reversing twice, and reversing an unknown event are all rejected.
- Reversing a settlement is rejected too: it would also need a hold to be re-instated or closed, and the brief doesn't define that.
- Reversing an instalment credit reverses all of its parts.

### 19. Does a reversal undo side effects (fees, interest)?

**Resolution:**
- Fees: no (§6).
- Interest: effectively yes. Accruals are recomputed from the corrected balances (§22).

This asymmetry is deliberate. The accrual journal is not the ledger, so it can self-correct without breaking "append-only" for ledger entries.

## Currency and amounts

### 20. Overdraft fee on a BHD account

**Problem:** the fee is defined as AED 25.00. ACC-002 is BHD, and no exchange rate is given.

**Resolution:**
- No fee is charged.
- An `EOD` error is logged so the gap is visible (`negativeBhdAccountIsReportedBecauseNoBhdFeeExists`).
- ACC-002 never goes negative in the scenario, so this does not change the output.

### 21. Amounts with more decimals than the currency allows

**Resolution:**
- Rejected, not rounded.
- Trailing zeros are fine: AED 1.000 is accepted as 1.00.
- "Amounts stored and rounded to their own precision" is read as: computed amounts are rounded; incoming amounts must already be exact.

### 22. "Three equal instalments" of 10.000 BHD

**Problem:** 10.000 / 3 has no exact 3-decimal answer.

**Resolution:**
- "Equal" is read as "as equal as the currency allows, summing exactly to the total".
- Result: 3.333, 3.333, 3.334. Method in NUMBERS.md.

## Interest

### 23. Interest base

**Resolution:**
- The closing ledger balance per value day, including that day's fee, excluding interest entries.
- Fees first, then interest: the brief says the closing balance, and the fee is part of it.
- Interest entries are excluded so the Day 6 capitalization does not earn interest on itself.

### 24. Backdated changes and interest

**Problem:** interest for Day 2 was accrued on 250.00 at the Day 2 close. E7 later makes Day 2 negative, then E9 makes it 225.00.

**Resolution:**
- Each close recomputes the rounded accrual for every value day so far.
- Where it differs from what is already journalled, a correcting line is appended.
- The Day 2 accrual goes +0.10 (Day 2 close), then −0.10 (Day 5 close), then +0.09 (Day 6 close), netting to 0.09.

**Why:** "the rounded daily accruals must sum exactly to the capitalized total" holds against the final, correct balances, and no journal line is edited.

### 25. Round each day, or round the total?

**Resolution:**
- Round each day to the currency's scale, then sum.
- The brief speaks of "rounded daily accruals" summing to the total, so rounding happens per day.
- The capitalization recomputes the per-day sum independently and throws if it disagrees with the journal. A mismatch is never discarded or absorbed.

### 26. "Positive balances only"

**Resolution:**
- A balance of zero or below accrues zero.
- No negative interest is charged on overdrawn days. The overdraft fee is the only penalty the brief defines.

### 27. Capitalization date, and what if the total is zero?

**Resolution:**
- One INTEREST credit per account, value date and booking date Day 6.
- If an account's total is zero, nothing is booked rather than a 0.00 entry.

## Reporting

### 28. "Closing ledger balance" per day, when later events rewrite earlier days

**Resolution:**
- Each day's headline balance is the value-day balance as known at that day's close.
- Each report also prints every earlier value day as known now, so a restatement is visible on the day it happens. On Day 5, Day 2 shows −395.00.

### 29. Acceptance criterion 1's phrase "evaluated at end of Day 5 and before any fee is assessed"

**Resolution:** read as: after all Day 5 events are applied, but before the Day 5 close runs fees. The test (`day2BalanceSeenOnDay5BeforeFeesIsMinus370`) checks exactly that point.

### 30. Opening balances of zero

**Resolution:** no entry is booked. See NUMBERS.md.
