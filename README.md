# In-memory account ledger

This is an append-only account ledger written in Java 21. It has no web layer, persistence, UI or database.
A replay script runs the brief's six-day event stream and prints a report for each day. A JUnit suite checks every acceptance criterion.

## Requirements

- JDK 21 or newer. `run.sh` finds Homebrew's `openjdk@21` if `java` is not on your PATH.
- Maven 3.9 or newer.

## Run

```bash
./run.sh                    # replay the event stream and print the six day reports
mvn test                    # 24 tests, all green
mvn test -Pdesign-gaps      # the one deliberately failing test (see below)
```

If Maven cannot find a JDK, set it first, for example `export JAVA_HOME=/opt/homebrew/opt/openjdk@21`.

## Reading the output

The replay starts with a `NOTE` line if any event is listed out of booking-day order. E10 is (see AMBIGUITIES.md §1). After that, each day prints one block:

```
==================== DAY 5 ====================
Events:
  E7   OK       posted AED -620.00 (backdated to value Day 2)
  E8   OK       Auth-B DECLINED (ledger AED -155.00 - holds AED 0.00 - this hold AED 90.00 = AED -245.00 < 0)
  E10  OK       posted BHD 10.000 as 3.333 + 3.333 + 3.334
Closing ledger balance (value date <= Day 5):
  ACC-001  AED      -230.00   available      -230.00
  ACC-002  BHD       10.000   available       10.000
  Value-day balances as known today (earlier days change when backdated entries arrive):
    ACC-001  D1 250.00 | D2 -395.00 | D3 5.00 | D4 -205.00 | D5 -230.00
...
Fee assessments:
  ACC-001  overdraft fee AED 25.00, value Day 2 (closing before fee -370.00)  <- backdated assessment
...
Interest accrual journal (lines appended today):
  ACC-001  Day 2    -0.10 on base -395.00  <- correction
...
Authorization states:
  Auth-B  ACC-001  DECLINED  AED 90.00 not held (since Day 5)
Errors:
  (none)
```

| Section | Meaning |
|---|---|
| **Events** | Each event booked that day. `OK` means it was applied. A declined authorization is still `OK`, because the decision itself was recorded. `REJECTED` means nothing was booked, and the reason is also listed under Errors. |
| **Closing ledger balance** | The sum of every entry whose value date is on or before this day, after fees. **Available** is the ledger balance minus active holds. |
| **Value-day balances** | The closing balance of every earlier value day, as known now. A backdated event changes these numbers, which is how E7 shows up on Day 5. |
| **Fee assessments** | Fees booked at this close. A fee for an earlier value day is marked `backdated assessment`. |
| **Interest accrual journal** | Interest is accrued in a separate journal, not in the ledger. When a backdated event changes an earlier day's balance, a `correction` line adjusts that day's accrual. On Day 6 the journal total is capitalized as one credit. |
| **Authorization states** | The latest state of each authorization: `APPROVED` (hold active), `DECLINED`, or `SETTLED` (hold released). |
| **Errors** | Rejected events, plus any end-of-day problems (`EOD`). |

## Results at a glance

| | ACC-001 (AED) | ACC-002 (BHD) |
|---|---|---|
| Day 1 | 250.00 | 0.000 |
| Day 2 | 250.00 (restated to −370.00 before fee once E7 arrives) | 0.000 |
| Day 3 | 650.00 | 0.000 |
| Day 4 | 465.00 (Auth-A settled 185.00; Auth-Z rejected) | 0.000 |
| Day 5 | −230.00 (E7 backdated; fees for Days 2, 4, 5; Auth-B declined) | 10.000 |
| Day 6 | 390.93 (E9 reverses E7; fees stand; interest 0.93) | 10.008 (interest 0.008) |

## The deliberately failing test

`DesignGapTest` is tagged `design-gap` and left out of `mvn test`. Running it with `-Pdesign-gaps` fails with `expected: <[]> but was: <[2, 4, 5]>`. The cause: the three fees triggered by E7 still stand after E9 cancels E7. The inline comment explains why the design does this and what business rule would fix it.

## Layout

Base package: `ae.mal.acc.ledger`

```
src/main/java/ae/mal/acc/ledger/
  app/
    Replay.java           entry point; replays a stream and prints the day reports
    Scenario.java         the brief's accounts and events
  config/
    LedgerConfig.java     business constants: overdraft fee, daily rate, window days
  dto/
    DayReport.java        per-day snapshot returned by a day close
    Outcome.java          result of applying one event
    AccountSnapshot.java  balances of one account at a day close
    FeeAssessment.java    one overdraft fee booked at a close
    LedgerError.java      a rejected event or end-of-day problem
  model/
    Account.java          account id and currency
    Currency.java         AED (2 dp), BHD (3 dp), rounding
    Event.java            sealed event types (credit, debit, authorization, settlement, reversal, instalment credit)
    LedgerEntry.java      immutable ledger line
    AuthRecord.java       immutable authorization log line
    Accrual.java          immutable interest journal line
  service/
    LedgerService.java    engine: apply events, close days, fees, interest, queries
  util/
    AmountSplitter.java   splits an amount into parts that sum exactly
src/test/java/ae/mal/acc/ledger/
  app/ScenarioTest.java          one test per acceptance criterion
  app/DesignGapTest.java         the deliberately failing test
  service/LedgerServiceTest.java rules in isolation
  util/AmountSplitterTest.java   exact-split property
```

## Other documents

- [NUMBERS.md](NUMBERS.md): every constant, and why it has that value.
- [AMBIGUITIES.md](AMBIGUITIES.md): every gap in the brief, and how it was resolved.
- [REJECTED.md](REJECTED.md): refused acceptance criteria, and approaches dropped along the way.
- [WORKLOG.md](WORKLOG.md): a timestamped log of the build.
