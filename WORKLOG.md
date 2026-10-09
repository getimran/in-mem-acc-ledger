# WORKLOG

Times are local (UTC+04:00). Each entry was written when the work happened, not reconstructed afterwards.

## 2026-10-09

- **17:41** Read the brief. Went through the acceptance criteria by hand before writing any code: worked out the ACC-001 balance per value-date day with and without E7. Already suspect four criteria (one-fee claim, fees reverting after E9, 3 × 3.334, discard remainder) and one trap (Auth-B approval is conditional on something that never happens).
- **17:42** Checked the toolchain: no `java` on PATH, but Homebrew OpenJDK 21 and 25 are installed and Maven 3.9 has JUnit 5.12.2 and Surefire 3.5.6 cached. Picked Java 21 (LTS) + Maven + JUnit 5 so the build works offline.
- **17:44** `git init`. Scaffolded `pom.xml` with a `design-gaps` profile, so the deliberately failing test is kept out of the default green run but is one flag away.
- **17:45** Domain types: `Currency` (scale per currency, HALF_EVEN for computed amounts, exact-only for inputs), sealed `Event` hierarchy, immutable `LedgerEntry` / `AuthRecord` / `Accrual` records, `DayReport`. Authorization state is an append-only log, not a mutable status field, so "no record is ever mutated" also holds for holds.
- **17:46** Ledger engine. Decided how backdating works: at every day close, walk value days 1..today earliest-first; book a fee for any negative day that has none yet (value date = that day, booked today). Interest uses the same walk but writes to a separate accrual journal: if a day's rounded accrual target changed, append a correcting line instead of editing the old one. Capitalization on Day 6 = journal total, and it throws rather than discard a mismatch.
- **17:47** Replay + `run.sh`. Hit the event-order trap: the brief lists E10 (Day 5) after E9 (Day 6). Strict list order would mean applying a Day 5 event after Day 6 closed, so I group by booking day with a stable sort and print a NOTE when it happens. First full run matches my hand numbers: Day 2 restated to -370.00 before fee, fees on value Days 2, 4 and 5, ACC-001 ends 390.93, ACC-002 ends 10.008.
- **17:47** Bug found in my own output: Day 4 printed "Errors: (none)" even though E6 was rejected. The per-day error slice started at close time, after the rejection was already logged. Now it starts when the day opens.
