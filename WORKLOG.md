# WORKLOG

Times are local (UTC+04:00). Each entry was written when the work happened, not reconstructed afterwards.

## 2026-10-09

- **17:41** Read the brief. Went through the acceptance criteria by hand before writing any code: worked out the ACC-001 balance per value-date day with and without E7. Already suspect four criteria (one-fee claim, fees reverting after E9, 3 × 3.334, discard remainder) and one trap (Auth-B approval is conditional on something that never happens).
- **17:42** Checked the toolchain: no `java` on PATH, but Homebrew OpenJDK 21 and 25 are installed and Maven 3.9 has JUnit 5.12.2 and Surefire 3.5.6 cached. Picked Java 21 (LTS) + Maven + JUnit 5 so the build works offline.
- **17:44** `git init`. Scaffolded `pom.xml` with a `design-gaps` profile, so the deliberately failing test is kept out of the default green run but is one flag away.
