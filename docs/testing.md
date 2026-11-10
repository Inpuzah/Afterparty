# Testing

## Automated

```sh
./gradlew clean build        # compile, all tests, shaded jar
./gradlew :core:test         # tests only
```

The repository tests run on a real PostgreSQL 18 using [zonky embedded-postgres](https://github.com/zonkyio/embedded-postgres). It downloads PostgreSQL binaries through Gradle, so no Docker or local install is needed, and it works the same on Windows, macOS, Linux and in CI.

| Test class | Covers |
|---|---|
| `PlayerProfileTest` | Defaults (level 1 / 0 XP / 0 coins), invariants, username rules, rename keeps identity. |
| `ProfileSessionManagerTest` | State transitions, stale tokens after quit or rejoin, closing sessions. |
| `DefaultProfileServiceTest` | Merging duplicate loads, per-caller timeouts, missing vs storage errors, shutdown, bounded queue. |
| `ClubCoreConfigTest` | Bundled defaults, missing env var, range and type validation, no password in `toString`. |
| `DatabaseManagerTest` | Startup fails within the timeout when the database is down. |
| `JdbcProfileRepositoryIntegrationTest` | Fresh migration and idempotency, single-row creation, rename without reset, 32 concurrent upserts produce one row, missing profiles, non-ASCII and 16-character names, `TIMESTAMPTZ` columns, `CHECK` constraints, unreachable database, persistence across a PostgreSQL restart. |
| `ProgressionEconomyMigrationIntegrationTest` | Fresh V2 schema, populated V1 upgrade without data changes, repeat migration startup, audit row constraints, primary/foreign keys, history indexes, and migration permissions for a schema-owning non-superuser. |
| `LevelCalculatorTest` | Exact level thresholds, level-100 cap, lifetime XP up to `Long.MAX_VALUE`, remaining XP, award request/result validation. |
| `DefaultProgressionServiceTest` | Off-thread execution, error propagation, input validation, bounded queue, caller timeouts, deadline/interrupted shutdown, and completing abandoned futures. |
| `JdbcProgressionRepositoryIntegrationTest` | Atomic XP/ledger updates, concurrent distinct and duplicate awards, conflicting payloads and cross-player operation-ID races, independent row locks, rollback injection, overflow, replay after repository recreation, timeout/retry, and async service-to-PostgreSQL flow. |
| `CurrencyOperationTest` | Whole-coin amounts, signed deltas, bounded metadata, result invariants and explicit insufficient-funds outcomes. |
| `DefaultEconomyServiceTest` | Credit/debit mapping, off-thread reads/writes, explicit failures, bounded queue, caller timeout/cancellation, and deadline/interrupted shutdown. |
| `JdbcEconomyRepositoryIntegrationTest` | Atomic balance/ledger writes, cap and overdraft enforcement, duplicate/conflicting operations, concurrent debits and mixed mutations, independent row locks, injected rollback failures, XP/coin isolation, restart persistence and timeout/retry. |
| `EconomyCommandsTest` | Permissions, syntax/amount/reason validation, online-name/UUID targeting, console behavior, audit payloads, optional limits, server-thread replies, disconnect/rejoin and timeout guards, tab completion, safe errors and `/profile` output after a join/refresh race. |
| `ProfileRefreshServiceTest` | Revision/token ordering, stale successes/failures, quit/rejoin, initial-load rejection, unavailable/missing/timeout behavior, offline/shutdown guards and invalidation on uncertain storage outcomes. |
| `DisplayConfigTest` | Strict MiniMessage/placeholder/length validation, typed config and interval bounds, duplicate/blank rows and animation wrapping. |
| `DisplayPreferenceIntegrationTest` | Independent persistent flags, ordered toggle/reconnect reads, fresh/V2-to-V3 migration, constraints and actual PostgreSQL restart persistence. |
| `DisplayManagerTest` | Shared task, per-viewer values, row diffing, safe fallbacks, persistent toggles, uncertain/timed-out saves, ownership handoff, cleanup across 20 reconnects, 100 mock viewers and reload atomicity. |
| `PlayerSettingsServiceTest` | Existing choices, immutable snapshots, one pending save, profile readiness, cancellation/timeout isolation, late loads, uncertain-write recovery, reconnect and shutdown guards. |
| `SettingsMenuControllerTest` | Player/permission checks, action slots, deferred single mutations, inventory movement/drag cancellation, pending/error labels, close/reopen, reconnect guards and shared API/cache updates. |
| `SocialRepositoryIntegrationTest` | V4 fresh/upgrade preservation, canonical UUID keys, viewer-relative relationship snapshots and private block hiding, duplicate/expired requests, transaction rollback, rate/inbox/cooldown limits, reciprocal request and accept/block races, actual PostgreSQL restart, async timeout recovery and Hikari exhaustion. |
| `DefaultSocialServiceTest` | Off-thread operations, typed/storage failures, input validation, queue rejection, caller timeout/cancellation isolation, normal/deadline/interrupted shutdown. |
| `SocialCommandTest` | Permissions, syntax/paging, online name and offline UUID targeting, committed notifications, generic privacy errors, local presence, main-thread replies and stale login guards. |
| `SocialConfigTest` | Existing-config defaults, interaction disable flag, explicit limits and invalid type/range rejection. |
| `PrivacyMenuControllerTest` | Loading/confirmed/pending/error states, permissions/readiness, deferred navigation, duplicate-click and inventory manipulation guards, reconnect/save ordering, stale callbacks, shutdown, timeout isolation and GUI-to-PostgreSQL policy enforcement without changing display choices. |
| `InteractionMenuControllerTest` | Sneak/main-hand/config/cancellation gating, deferred opens/actions, explicit relationship actions, cached target preview, inventory protection, viewer/target reconnect and permission guards, pending write ordering, timeout/failure recovery, shutdown and real GUI-to-PostgreSQL accept/block/unblock/privacy enforcement. |

Settings integration extends `DisplayPreferenceIntegrationTest` to exercise the
unified API against V3 choices, missing-row defaults, SQL rejection/rollback,
service recreation and an actual PostgreSQL process restart. No V4 is needed.
Menu tests inject a renderer to exercise event/lifecycle logic without Paper's
live item registry; actual item rendering and client behavior require
[settings manual acceptance](testing/settings-manual-acceptance.md).
Interaction tests also inject the ordinary-inventory predicate because Paper's
inventory-type registry needs a live server; actual crafting/creative views and
other plugins' protection/GUI event ordering remain staging checks.

The coin repository integration suite also wires real profile, coin and XP
services to PostgreSQL and checks post-commit cache refresh, replay freshness and
insufficient-funds behavior. Command tests use lightweight Bukkit interface test
doubles; they are not a substitute for running the new commands on staging Paper.

V2 migration tests use disposable embedded PostgreSQL only. They do not apply
schema changes to Neon or validate staging backups. See the
[progression/economy deployment gate](progression-and-economy.md#deployment-gate-not-cleared)
before deploying a jar that includes V2.

V3 is now included as well. The ledger-specific migration tests explicitly target
V2 to preserve that regression coverage; full-install profile and display tests
exercise V3. None of these tests migrate a live database.

On the v0.4 development branch full-install tests exercise V4; ledger-specific
tests remain pinned to V2. The social suite explicitly upgrades populated V3 and
verifies existing profile/display values. See the [social staging checklist](social.md#upgrade-and-verification)
before deploying a snapshot; real-client testing and backup/restore remain open.

## VS Code diagnostics

If Gradle builds successfully but VS Code cannot resolve Flyway or JUnit imports,
run **Java: Reload Projects** from the Command Palette. If stale errors remain,
run **Java: Clean Java Language Server Workspace** and allow the window to reload.
The workspace enables automatic Gradle build-configuration updates and explicitly
associates the Bukkit schema with `plugin.yml`; do not add an unrelated `api` key
to satisfy a misdetected YAML schema.

## Manual Paper acceptance checklist

Use a separate test server. Record the exact Paper build and Java version in the release notes.

- [ ] Server starts with ClubCore enabled. The log shows `Schema migration complete` and `Profile, progression and economy services ready`, with no warnings or errors.
- [ ] `/plugins` lists ClubCore.
- [ ] First join creates exactly one row: `SELECT * FROM club.player_profiles;`
- [ ] `/profile` shows name, Level 1, 0 XP, 0 Coins and today's date (UTC).
- [ ] `/profile foo` shows `Usage: /profile`.
- [ ] Console `profile` prints "This command can only be used by a player."
- [ ] A player without `club.profile.view` is denied.
- [ ] `/balance` shows the saved balance; console and extra arguments are rejected.
- [ ] Non-admin players cannot use either `/clubadmin` mutation permission.
- [ ] Grant 100 XP, add 100 coins, remove 75 coins. `/profile` refreshes to level 2,
  100 XP and 25 coins; `/balance` shows 25. While a refresh is pending, `/profile`
  shows loading, never an old ready snapshot.
- [ ] Offline names are rejected. A known offline UUID works; an unknown UUID
  is rejected without creating a profile. Actor/source/reason are in the ledger.
- [ ] Try a removal larger than the balance and a credit exceeding the cap;
  neither changes data. Test configured limits as well as default unlimited mode.
- [ ] Spam concurrent admin operations, disconnect/rejoin during writes and
  restart the server; verify persisted totals, ledger and current-session replies.
- [ ] Stop the database during an admin command: no success-shaped response,
  no SQL details in chat, and operation UUID logged for investigating unknown outcomes.
- [ ] Rejoin and server restart both keep `first_joined_at` and the stats unchanged.
- [ ] Two different accounts get separate rows.
- [ ] Join, disconnect immediately and rejoin: no stale messages, and `/profile` works.
- [ ] Stop PostgreSQL while players are online, then rejoin. "Profiles are temporarily unavailable." appears, `/profile` says the same, and TPS stays at 20 (check with `/tps` or `/spark tps`).
- [ ] Start the server with PostgreSQL down: ClubCore logs a clear error within about `connection-timeout-ms` and disables; the server keeps running.
- [ ] Start PostgreSQL again and restart the server: everything works normally.
- [ ] Logs contain no passwords and no unexpected stack traces.
- [ ] Follow the [display staging checklist](displays.md#verification-and-release-gates):
  live animation, per-player stats, persisted toggles, valid/invalid reloads,
  scoreboard handoff, repeated join/quit cleanup and load/TPS validation.
