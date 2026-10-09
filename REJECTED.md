# REJECTED

## Acceptance criteria

The criteria are numbered in the brief's order.

| # | Criterion | Verdict |
|---|---|---|
| 1 | Day 2 balance, seen at end of Day 5 before fees, is AED −370.00 | **Accepted** |
| 2 | E7 causes exactly one overdraft fee, on Day 2 | **Refused** |
| 3 | Day 4 settlement of Auth-A is accepted | **Accepted** |
| 4 | Settlement against an unknown authorization is rejected; no funds leave | **Accepted** |
| 5 | If Auth-B is approved, its hold reduces available but not ledger balance | **Accepted as a rule; premise is false** |
| 6 | After E9, all balances and fees return to pre-E7 values | **Refused** |
| 7 | The three BHD instalments are each 3.334 | **Refused** |
| 8 | If rounded accruals don't sum to the capitalized total, discard the remainder | **Refused** |

### AC2: refused. E7 causes three fees, not one

When E7 (−620.00, value Day 2) arrives on Day 5, every value day from Day 2 onward is re-evaluated (AMBIGUITIES §3):

| Value day | Balance before fee | Fee? | After fee |
|---|---|---|---|
| 2 | 250.00 − 620.00 = **−370.00** | yes | −395.00 |
| 3 | −395.00 + 400.00 = 5.00 | no | 5.00 |
| 4 | 5.00 − 185.00 = **−180.00** | yes | −205.00 |
| 5 | **−205.00** | yes | −230.00 |

Without E7, none of these days is negative (Day 4 would be 465.00). So E7 causes three fees.

"Exactly one" holds only if Day 4 and Day 5 are never re-assessed, which contradicts the rule that every day whose closing balance is negative gets a fee.

Test: `ScenarioTest.e7CausesThreeBackdatedOverdraftFeesNotOne`.

### AC6: refused. Fees don't revert, so balances don't either

E9 books a new +620.00 entry. It doesn't delete E7, and it can't delete the three fees, because "no event record is ever mutated or deleted".

| | Before E7 | After E9 |
|---|---|---|
| Day 6 balance before interest | 465.00 | 390.00 |
| Fee entries | 0 | 3 |

The criterion can't hold without breaking the append-only rule. Even a refund policy wouldn't satisfy it as written: refunds would add new entries, so "fees" would not be back at their pre-E7 value of zero entries.

Test: `ScenarioTest.reversalOfE7LeavesFeesInPlace`. Whether the fees *should* be refunded is a separate question, raised by `DesignGapTest`.

### AC7: refused. 3 × 3.334 = 10.002

Three instalments of 3.334 credit BHD 10.002, which is 0.002 more than was paid in. The parts must sum to the event's total.

- Correct split: 3.333 + 3.333 + 3.334 = 10.000.
- "Equal" can't be literal at 3 decimal places, because 10.000 / 3 = 3.3333….

Test: `ScenarioTest.bhdInstalmentsSumExactlyToTen`.

### AC8: refused. A remainder is never discarded

The brief's own rule says "the rounded daily accruals must sum exactly to the capitalized total". Discarding a remainder is exactly the case where they don't sum, so the criterion contradicts the rule it sits next to.

What the design does:
- The capitalized amount *is* the accrual journal's total.
- At capitalization, the per-day accruals are recomputed independently. If they disagree with the journal, the ledger throws `IllegalStateException`. A mismatch means a bug, and should stop the run rather than lose money quietly.

Test: `ScenarioTest.capitalizedInterestEqualsSumOfRoundedDailyAccruals`.

### AC5: not refused, but flagged

The rule is correct: a hold reduces available balance and never touches the ledger (`LedgerTest.approvedHoldReducesAvailableButNotLedgerBalance`).

But Auth-B is not approved:
- When E8 arrives, E7 has already been applied, so the ledger balance is −155.00.
- −155.00 − 90.00 = −245.00, which is below zero, so Auth-B is declined.

The criterion is phrased to make a reader assume approval. So is the brief's "Auth-B is never settled inside the window". An implementation that approves Auth-B because "the criterion says so" breaks the authorization rule.

Test: `ScenarioTest.authBIsDeclinedSoItsHoldNeverApplies`.

### AC1, AC3, AC4: accepted

- **AC1:** 1200.00 − 950.00 − 620.00 = −370.00. The 200.00 hold is not a ledger entry.
- **AC3:** Auth-A exists, is APPROVED, belongs to ACC-001, and 185.00 ≤ 200.00.
- **AC4:** Auth-Z was never authorized. E6 is rejected and books nothing.

## Approaches abandoned

Some of these were built and then changed. Others were considered and dropped before any code was written. Each is labelled.

### Built, then changed

1. **Per-day error list sliced from the start of the day close.** The first replay printed "Errors: (none)" on Day 4 even though E6 had been rejected. The error slice started when `closeDay()` began, but event rejections are logged during `apply()`, before that. It now starts when the day opens. Found by reading my own output against the hand calculation.

2. **Wrong numbers in the failing test's annotation.** I first wrote that value Days 4 and 5 end at 440.00 and 415.00 once E7 and E9 cancel. Those figures still included fees. The test compares balances *excluding* fees, which are 465.00 and 465.00. Corrected before commit.

3. **Relying on Maven's default `clean` plugin.** `mvn clean test` failed offline because the default clean plugin version wasn't cached. I had logged the check as passing before reading its output (see WORKLOG). The clean plugin is now pinned to a cached version, like the other plugins.

### Considered and dropped before coding

4. **Replaying strictly in list order.** This would apply E10 (Day 5) after Day 6 closed. The options were to reject E10 as an error or to accept a Day 5 booking into a closed day. Both are worse than honouring the event's own booking day (AMBIGUITIES §1).

5. **Dating backdated fees on the assessment day.** Booking the Day 2 overdraft fee with value date Day 5 leaves Day 2 negative without a fee and charges Day 5 twice (AMBIGUITIES §2).

6. **Posting daily interest accruals as ledger entries.** This is the obvious design, but backdating breaks it.
   - The Day 2 accrual of +0.10, posted at the Day 2 close, is wrong after E7.
   - Correcting it would need reversal entries in the ledger for every restated day.
   - It would also contradict "accruals capitalize as a single credit".

   Keeping accruals in a separate append-only journal and posting one credit on Day 6 satisfies both rules.

7. **Rounding instalments with HALF_UP.** It gives 3.333 here, but it overshoots for other totals (2.00 / 3 gives 0.67 × 3 = 2.01). See NUMBERS.md.

8. **Absorbing an accrual remainder into the last day.** This is the usual way to "make it add up", but it is AC8 in disguise: the last day's accrual would no longer be its own rounded value.

9. **A mutable `status` field on an authorization.** Changing APPROVED to SETTLED in place is a mutation. Authorization history is an append-only log instead, and the current state is the latest record.

10. **Removing fees when E9 reverses E7.** Ruled out by the append-only rule. Kept as the documented design gap instead.

11. **Re-declining Auth-A after E7 restates Day 2.** Ruled out (AMBIGUITIES §7). A decision is final once made on the information available at the time.
