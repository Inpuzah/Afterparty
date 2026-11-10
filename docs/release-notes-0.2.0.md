# ClubCore 0.2.0 - staging candidate

This change combines progression, economy, commands, cache integration and lobby
displays on top of the v0.1 profile foundation. It is not a production acceptance
sign-off. Target: Java 25 and Paper 26.3.

## Added

- Atomic lifetime XP awards with a level-100 display cap; lifetime XP keeps growing.
- Atomic Club Coins credits/debits, a 1,000,000,000 credited-balance cap,
  overdraft protection and durable successful-operation replay.
- V2 coin/XP audit ledgers with operation UUID, source, reason and optional actor.
- `/balance`, `/clubadmin xp add`, and `/clubadmin coins add|remove`.
  Admin command amounts default to unlimited; optional configuration limits do
  not override domain balance/overflow rules.
- Login-token/read-revision guarded profile refreshes after mutations.
- Animated Adventure bossbar and per-viewer sidebar with cached placeholders,
  one shared scheduler, row diffing and explicit lobby ownership.
- `/display status`, `/display bossbar on|off`, `/display scoreboard on|off`,
  and permission-controlled `/clubadmin displays reload`.
- V3 persistent display preferences, asynchronous ordered saves/loads, validated
  atomic config replacement, cleanup and future-game suppression API.

Existing UUID identity, first-join history, `/profile` and narrow join/quit
timestamp writes are preserved. No activity rewards, shops, trading, game logic
or optional display-preview command are included.

## Upgrade and rollback

1. Use a separate staging server/database. Take a backup and test restoration.
2. Build with Java 25 using `.\gradlew.bat clean build` on Windows.
3. Stop the server and replace the old plugin with
   `core/build/libs/ClubCore-0.2.0.jar`; do not install both jars.
4. Review runtime configuration. Existing files inherit bundled display defaults:
   **both displays are on by default and ClubCore owns the lobby sidebar**.
   Disable competing sidebar plugins or use the documented suppression API.
5. On startup, Flyway automatically applies pending V2 and V3 migrations.
   V1/V2 are not rewritten; V3 adds only preferences. Confirm migration success
   and compare existing profiles/audit data before continuing.
6. Follow [testing.md](testing.md) and [displays.md](displays.md), including
   permissions, reconnect/restart, database outage and multi-player tests.

Changing the jar back does not reverse migrations or restore old totals.
Do not delete Flyway history or edit applied migrations. Use a verified database
restore or reviewed forward-fix migration when a database rollback is required.

## Verification

Local Windows / Java 25 verification on November 9, 2026:

```text
.\gradlew.bat clean build --console=plain
BUILD SUCCESSFUL in 1m 13s
187 tests, 0 failures, 0 errors, 0 skipped
```

Tests use disposable embedded PostgreSQL 18, including migration upgrades,
transaction races, rollback injection, process-restart persistence, timeout
replay, cache ordering, 20 display reconnect cycles and 100 mocked viewers.
The shaded jar's version, display classes, commands/permissions and V3 resource
were inspected. Mock viewers do not prove actual client rendering or TPS.

Earlier foundation smoke tests used Paper 26.3 build 244, Java 25 and PostgreSQL
18.6. The new v0.2 commands/displays have not yet been smoke-tested on live Paper.
GitHub Actions results must be checked on the pull request; local results are
not evidence that remote CI passed.

## Operational cautions and remaining gates

- Back up before deployment; live migrations were not run during development.
- Do not repeat timed-out admin grants blindly: each command creates a new UUID.
  Inspect its logged ID/ledger first; the original operation may have committed.
- Cached profiles may show loading/unavailable during refresh; `/balance` reads
  the authoritative total asynchronously.
- Display toggles survive restart, but an uncertain save needs status/reload
  verification. Config reload affects displays only.
- Validate live animations, flicker, permissions, ownership, restart persistence,
  TPS and memory under realistic load before release.
- Java does not consume Neon's pulled `DATABASE_URL` values automatically.
  Neon environment wiring and pooled-runtime/direct-migration separation remain
  unfinished. No live Neon database was changed.
- Single-server ownership only; no cross-server synchronization.

Further details: [progression/economy](progression-and-economy.md),
[coin operations](operations-economy.md), [configuration](configuration.md),
[database](database.md), and [displays](displays.md).
