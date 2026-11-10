# Progression and economy: v0.2 implementation status

## Current slice: lobby displays (VISUAL-001-009)

Work starts from baseline commit `6de22b0` on
`feat/core-progression-economy`. The baseline clean build passed all 44 tests.
The existing Java 25 / Paper 26.3 plugin, JDBC/HikariCP stack, profile API and
immutable model are preserved. The `progression` and `economy` backends are now
bootstrapped by `ClubCorePlugin`, with public service accessors, bounded workers
and shutdown before the shared data source closes.

V2 adds two audit tables alongside `player_profiles` in the configured database
schema/search path:

| Table | Contents |
|---|---|
| `currency_transactions` | Operation UUID, player UUID, signed nonzero delta, resulting nonnegative balance, operation type, source, reason, optional actor UUID, timestamp. |
| `experience_awards` | Operation UUID, player UUID, positive amount, resulting nonnegative lifetime XP, resulting level >= 1, source, reason, optional actor UUID, timestamp. |

Both tables have a primary key on `operation_id`, a foreign key to
`player_profiles.uuid`, and a `(player_uuid, created_at DESC)` history index.
Foreign keys prevent deleting a player while their audit rows exist.
V2 does not rewrite profiles, reset balances or XP, or change any V1 columns.
V1 remains unchanged.

Coins and XP have transactional repositories, async services, permission-controlled
admin commands and automatic online profile refreshes. The separate display slice
adds animated bossbar/sidebar, cached placeholders, persistent V3 preferences and
display reload; see [displays.md](displays.md). Ordinary activity rewards and
player transfers are not enabled.
Profile columns remain the authoritative totals. XP totals and audit rows are
written in the same transaction. Coin balances and their audit records are
likewise committed atomically.

Operation IDs are unique within each table, not across the coin and XP ledgers.
Both repositories implement payload comparison and original-result replay;
the schema alone cannot provide these guarantees. Coin debits enforce
insufficient-funds checks under the profile row lock. Callers must use fresh
operation UUIDs for new actions and reuse them only for retries of the same request.

## Club Coins rules and API

- One currency, **Club Coins**, stored as nonnegative Java `long` / PostgreSQL
  `BIGINT`, never floating point.
- The maximum balance for new credits is **1,000,000,000 coins**. Exact-cap
  credits succeed; exceeding the cap fails atomically.
- Amounts must be positive. Arithmetic is guarded and exact; even
  `Long.MAX_VALUE` requests cannot wrap a balance.
- Existing balances above the cap are readable and can be debited, not credited.
  This slice does not rewrite or clamp old data, and adds no new migrations.
- Failed insufficient-funds attempts change no columns or timestamps, create no
  ledger row, and do not reserve an operation ID. The same request may succeed
  after funding; there is no durable failed-attempt audit or replay record.
- Only committed successes have guaranteed original-result replay. A replay
  returns the original previous/resulting balance, not today's balance.

```java
EconomyService service = plugin.economyService();

UUID operationId = UUID.randomUUID(); // retain for retries of this specific action
CompletableFuture<CurrencyOperationResult> credit = service.credit(
        operationId, playerId, 100L, "activity", "Completed activity", actorId);
```

`balance(playerId)` returns an authoritative `BalanceSnapshot`.
`credit(...)` and `debit(...)` return a result containing the exact request,
`SUCCESS` or `INSUFFICIENT_FUNDS`, previous/new balance, and `duplicateReplay`.
Player UUID, positive amount, credit/debit type, source, reason and nullable actor
UUID must all match on replay. Source/reason validation uses the same bounds as XP.

`JdbcEconomyRepository` uses `READ COMMITTED`, checks existing operations before
and after `SELECT ... FOR UPDATE`, and updates only `coins` and `updated_at`.
The signed delta and resulting balance are inserted into the ledger within the
same transaction. Failures roll back the profile update as well as the ledger.
Concurrent operations for different players do not share profile-row locks.
For a same-ID/different-player race, a uniqueness violation rolls back the loser
before inspecting the winner and reporting the payload conflict.

| Coin failure | Signal |
|---|---|
| Overdraft | Result status `INSUFFICIENT_FUNDS`; no ledger entry. |
| Credit would exceed cap or overflow | `EconomyException` with `BALANCE_LIMIT_EXCEEDED`. |
| Missing persisted player | `PROFILE_NOT_FOUND`; no player created. |
| Operation ID reused with different payload | `OPERATION_CONFLICT`. |
| Shutdown or full queue | `SERVICE_UNAVAILABLE`. |
| Invalid amount/metadata | Future fails with `IllegalArgumentException` (required null fields: `NullPointerException`). |
| Database failure | `ProfileStorageException`; never a success or insufficient-funds fallback. |

Both backend services reuse `BoundedServiceExecutor` for bounded queues,
independent caller futures, deadline-based drain and explicit failure of
unresolved requests. Each owns its executor. Stop each service before closing its
data source. Timeout or caller cancellation does not undo an accepted request:
retry unknown outcomes with the same operation ID and payload.

These APIs are trusted server-side interfaces, not player permissions.
Admin authorization and optional command amount limits are enforced by the
command adapter, not by trusted backend callers. No player transfers or purchases
are enabled.

## XP rules and API

- XP is cumulative lifetime XP; crossing a level threshold does not consume it.
- The threshold to reach level `L` is `100 * L * (L - 1) / 2`.
- **Displayed levels are capped at 100** (495,000 XP). Lifetime XP keeps growing
  after that cap, and remaining XP to the next level is zero.
- Supported totals are `0..Long.MAX_VALUE`. Awards must be positive;
  `Math.addExact` rejects overflow without changing the profile or ledger.
- `experience` is authoritative. Level calculation derives from XP, and each
  award writes the derived materialized `level` for compatibility. Existing
  profiles are not bulk rewritten; an inconsistent old level is normalized on
  the next successful XP award.
- The service is a trusted backend API, not an authorization boundary. Admin
  permissions and optional command grant limits are enforced by the command adapter.

```java
ProgressionService service = plugin.progressionService();

// Generate once per action; retain this ID for retries, including after a timeout.
UUID operationId = UUID.randomUUID();
CompletableFuture<XpAwardResult> award = service.awardXp(
        operationId, playerId, 100L, "activity", "Completed activity", actorId);
```

`ProgressionSnapshot` exposes player UUID, lifetime experience, derived level and
XP remaining. `XpAwardResult` includes the immutable request, previous/new XP,
previous/new derived levels and a `duplicateReplay` flag. A replay returns the
original totals even if later awards have changed the current profile.
`source` (1-80 characters) and `reason` (1-255) are nonblank and reject control
characters; their exact values and the optional actor UUID are part of the retry
payload. They are never trimmed or silently rewritten.

### Transaction and failure semantics

`JdbcProgressionRepository` uses `READ COMMITTED` and a per-player
`SELECT ... FOR UPDATE` lock, checking the operation ID before and after the
lock. It updates only `experience`, `level`, and `updated_at`, inserts the audit
row, and commits atomically. Exceptions roll back the transaction.

Different-player requests do not share row locks. If conflicting requests use
the same operation ID concurrently on different players, the ledger primary key
selects a winner. The loser rolls back its profile write before examining the
winning payload and reporting an operation conflict.

| Failure | Signal |
|---|---|
| Invalid amount/metadata | Future fails with `IllegalArgumentException` (or `NullPointerException` for required null inputs). |
| Missing persisted player | `ProgressionException` with `PROFILE_NOT_FOUND`; no player is created. |
| Retry ID with different player/amount/source/reason/actor | `OPERATION_CONFLICT`; no additional award. |
| Total would exceed `Long.MAX_VALUE` | `XP_OVERFLOW`; no changes. |
| Shutdown or full bounded worker queue | `SERVICE_UNAVAILABLE`. |
| Database/query failure | Existing `ProfileStorageException`, with operation ID and SQL cause for server-side logging. |

`DefaultProgressionService` owns its bounded executor. Callers receive independent
future copies, so caller timeout/cancellation does not cancel accepted JDBC work.
Invoke `shutdown(timeout)` before closing its data source; it stops accepting work,
drains until the deadline, then fails unresolved futures explicitly. A running
database transaction may still have committed despite a timeout or interrupted
shutdown: **retry the same operation ID and exact payload**, never generate a new
ID just because the outcome was unknown.

No Bukkit APIs are used by this package. Use the plugin-managed service for
gameplay integration: its mutation observer invalidates and refreshes online
snapshots before exposing a committed result to callers. Refresh completion is
asynchronous, so `/profile` may briefly show loading. Direct repository calls or
standalone service factories without an observer do not maintain the online cache.
Callers must still schedule Bukkit messages/events onto the server thread.

## Commands, lifecycle and freshness

The plugin registers:

| Command | Permission (default) |
|---|---|
| `/balance` | `club.balance.view` (everyone), player-only |
| `/clubadmin xp add <online-player\|uuid> <amount> [reason]` | `club.admin.xp` (op) |
| `/clubadmin coins add <online-player\|uuid> <amount> [reason]` | `club.admin.coins` (op) |
| `/clubadmin coins remove <online-player\|uuid> <amount> [reason]` | `club.admin.coins` (op) |

Admin targets are resolved from online exact names or canonical UUIDs; the backend
rejects UUIDs without persisted profiles. No offline-name lookup or profile
creation is performed. Amounts must be positive integral `long` values. User-selected
per-command defaults are **unlimited** (`admin-commands.max-xp/max-coins: 0`);
nonzero configured limits are validated and enforced. Domain limits always apply.
Reason defaults to `Administrator command`; supplied metadata is validated before
submission. Ledger source is `admin-command`, with player actor UUID or null for console.

Every invocation generates/logs a new operation UUID and replies on the main thread
only after a confirmed result, or with an explicit unknown-outcome/error message.
`profile.load-timeout-ms` bounds response waits, not JDBC mutation lifetime.
Repeating a timed-out admin command is not an idempotent retry: inspect its logged
ID first. There is no command for resubmitting an existing operation ID.
Replies for a disconnected or replaced initiating session are suppressed; the
accepted operation is not cancelled.

`ProfileRefreshService` invalidates the online snapshot after successful mutations
(including replay) and after uncertain storage failures. It increments a read
revision without changing the login token, then uses `ProfileService.findById`
off-thread. Only the matching `(uuid, token, revision)` can publish ready/unavailable
state. Out-of-order reads, old failures and results from a prior login cannot
replace a newer snapshot. Initial join completion after invalidation triggers a
fresh read for that same login rather than publishing its stale upsert result.

Reads are bounded and timed out. Failure or missing data makes `/profile`
unavailable, not deceptively current. A failed refresh does not reverse a successful
mutation or report it as failed. Offline targets have no cached session to refresh.
`/profile` remains an instantaneous snapshot reader; `/balance` always performs an
asynchronous authoritative read. Existing profile join/quit writes still update
only identity/timestamp columns, never stale currency or progression values.

Shutdown closes the refresh coordinator and sessions, queues last-seen writes and
stops display animations and drains display preference/config workers as well as
economy, progression and profile services (10 seconds each) before closing Hikari.
Worker APIs do not publish Bukkit reward events.

## Automated migration validation

`ProgressionEconomyMigrationIntegrationTest` runs against disposable embedded
PostgreSQL, with a separate schema per case:

- Fresh database migrates through V1 and V2.
- Populated V1 upgrades to V2 with every profile column unchanged.
- Repeated migration startup preserves audit rows and applies no migrations.
- Credit, debit, XP, optional actors, indexes and timezone-aware timestamps work.
- Invalid amounts, totals, levels, null metadata and unknown players are rejected.
- Duplicate operation IDs are rejected within each ledger.
- Audit rows cannot become orphaned through profile deletion.
- A non-superuser role owning its schema can migrate and insert records.

The existing repository tests now clean all related tables together and expect
schema version 2. Run both migration and repository coverage with:

```powershell
.\gradlew.bat :core:test --tests '*ProgressionEconomyMigrationIntegrationTest' --tests '*JdbcProfileRepositoryIntegrationTest'
```

### XP validation

Run the XP slice with:

```powershell
.\gradlew.bat :core:test --tests '*progression.*'
```

Coverage includes every supported level boundary, XP at `Long.MAX_VALUE`, capped
level behavior, request validation, worker-thread execution, bounded queue rejection,
timeout/shutdown behavior, and real PostgreSQL transactions:

- Distinct concurrent awards accumulate without lost updates.
- Concurrent identical requests grant once and return original totals.
- Different payload fields or different players with the same ID are rejected.
- A timeout followed by a retry does not double-grant XP.
- Independent players can progress while another player's row is locked.
- An injected ledger-insert failure rolls back the full profile update.
- Recreated repository instances replay committed awards from the database.
- Missing profiles, unavailable databases and overflow never return success.

The XP slice originally passed 97 tests (36 progression tests). The full build
verification below includes its regressions after shared-worker extraction.

### Coin validation

```powershell
.\gradlew.bat :core:test --tests '*economy.*' --tests '*DefaultProgressionServiceTest'
```

Coin unit/service tests cover request/result invariants, off-thread work, bounded
queue rejection, caller timeout/cancellation, failure propagation and shutdown.
Real PostgreSQL tests cover:

- Credits/debits and exact-cap behavior without touching XP or identity columns.
- Two concurrent 75-coin debits on a 100-coin balance: one success, one rejection.
- Concurrent credits at the cap: one success, one limit failure.
- Distinct mixed operations maintain agreement between balances and ledger sums.
- Duplicate credits/debits grant or charge once, including retries after timeout.
- All payload conflicts, including credit versus debit, are rejected.
- Cross-player ID conflicts roll back the losing player's update.
- Ledger-insert failure rolls back credits and debits, allowing safe retry.
- Coins and XP awarded concurrently do not overwrite each other's fields.
- Legacy balances, unavailable databases and missing profiles have explicit behavior.
- Balance and original-result replay survive a PostgreSQL process restart.

Verification on November 9, 2026: `clean build` passed **187 tests**, with zero
failures, errors or skipped cases. This includes 25 display tests, 16 economy
command tests, 9 profile-refresh tests and real PostgreSQL service-to-cache and
preference persistence coverage, alongside transaction/concurrency regressions.
The shaded `ClubCore-0.2.0.jar` contains both backends, display services, commands,
the refresh service, shared worker helper and V1/V2/V3 migrations; metadata reports
version `0.2.0` and the new permissions.
Timeout/retry tests hold the original response after a real commit so retry
ordering does not depend on thread scheduling.

## Deployment gate: not cleared

No deployed/local persistent database or Neon branch was modified during this
implementation. Tests use disposable databases; they do not prove staging setup,
backup restoration, real-client gameplay, or Neon connectivity.

The plugin applies pending migrations automatically at startup. **Do not deploy
this jar to production yet.** Before staging deployment:

1. Complete baseline in-game `/profile`, rejoin, permission and outage checks.
2. Confirm the target database is development/staging, then back it up and test
   restoring it into an isolated database.
3. If using Neon, resolve Java connection configuration and use a direct
   connection for migrations; pooled runtime/direct migration separation is not
   yet implemented.
4. Verify the migration account owns/can create tables in the configured schema.
5. Apply V2/V3 on staging and compare existing profile data before and after.

Do not edit applied V1/V2/V3 migrations. An old plugin jar does not reverse database
migrations; use a tested backup restore or a forward-fix migration when required.

## Next slices

1. Manual Paper staging acceptance of the new commands, persistence, outage and
   permissions behavior, plus backup/restore and remote CI verification.
2. Live display validation: animations, preferences, scoreboard ownership, TPS
   and retention under realistic load. Optional staff-only preview remains deferred.
