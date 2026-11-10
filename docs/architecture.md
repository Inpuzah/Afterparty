# Architecture

ClubCore v0.3 adds unified player settings to profiles, XP, Club Coins and lobby displays for a single
Paper 26.3 server.

## Layers

```text
PlayerSessionListener / ProfileCommand / BalanceCommand / ClubAdminCommand
        │                                  (server thread; Bukkit API)
        ▼
  ProfileService / ProgressionService / EconomyService   (bounded async workers)
        │                           │
        ▼                           ▼
  JDBC repositories          ProfileRefreshService → ProfileSessionManager
        │                           (immutable, token/revision-checked snapshots)
        ▼
  DatabaseManager (HikariCP) + Migrations (Flyway) → PostgreSQL
```

| Package | Responsibility |
|---|---|
| `config` | Load and validate `config.yml`; prefer `CLUB_DB_PASSWORD`, falling back to runtime `database.password`. |
| `model` | `PlayerProfile` immutable record with validation. |
| `persistence` | Connection pool and schema migrations. |
| `repository` | SQL only. Throws `ProfileStorageException` when the database fails. |
| `service` | Async profile API, duplicate-load merging, profile refresh coordination and shared bounded workers. |
| `progression` / `economy` | Trusted async APIs, immutable requests/results and transactional JDBC repositories with audit/idempotency guarantees. |
| `session` | `ProfileSessionManager` tracks `LOADING → READY / UNAVAILABLE → CLOSED` with login tokens and refresh revisions. |
| `listener` | Join/quit lifecycle. |
| `command` | Cached `/profile`, async authoritative `/balance`, permission-controlled `/clubadmin`. |
| `display` | Shared animation task, per-viewer bossbar/sidebar, cached placeholders, validated config and async persistent preferences. |
| `settings` | Immutable choices, one settings-session cache, async mutation API and `/settings` GUI. Reuses the V3 display repository/worker. |

## Threading rules

- No gameplay SQL on the server thread. Profile, XP and coin services each own `pool-size` workers and
  a bounded queue of 1,000, sharing one Hikari connection pool. Startup connection
  and migration checks still run synchronously before services are exposed.
- Display preferences use one bounded worker to preserve save/load order.
  Config reloads use a separate bounded worker for file IO and parsing.
- Full queues fail explicitly (`ProfileServiceClosedException` or the domain's
  `SERVICE_UNAVAILABLE`), rather than queueing without limit.
- Results return to the server thread through `BukkitScheduler#runTask` before any Bukkit API is touched. Results that arrive after the plugin is disabled are dropped.
- Repository workers handle plain values (UUID, username, immutable snapshots),
  never `Player` objects. Mutation hooks invalidate cached snapshots immediately
  and enqueue fresh reads; they do not call Bukkit APIs.

## Session lifecycle

1. **Join** (`PlayerJoinEvent`, MONITOR): `sessions.begin(uuid)` creates a `LOADING` session with a unique token. Then `profiles.getOrCreate(uuid, name)` runs with a timeout of `profile.load-timeout-ms`.
2. **Load completes** (server thread):
   - `markReady(uuid, token, profile)` applies only to the initial revision of that
     login. Late results after quit/rejoin or mutation refresh are rejected.
   - If the initial join upsert finishes after a refresh invalidated its snapshot,
     its completion starts another authoritative read for that same login. This
     avoids leaving a session unavailable when a refresh raced profile creation.
   - On failure or timeout, the session becomes `UNAVAILABLE`. The root cause is logged, and the player sees only "Profiles are temporarily unavailable."
3. **Quit**: the session is removed, so late callbacks are ignored, and `last_seen_at` is updated asynchronously. Write failures are logged.
4. **Shutdown**: stop new refreshes, close sessions and queue last-seen writes.
   Stop the display task, release viewer resources and drain display config/
   preference workers first.
   Drain economy, progression and profile workers before closing Hikari, with a
   10-second allowance per service. Unresolved coin/XP requests fail explicitly;
   their in-flight commits may still have succeeded.
5. **Reload**: players who are already online when the plugin enables get a new session.

`last_seen_at` is best-effort: `PlayerQuitEvent` does not fire if the server crashes.

## Mutation freshness

The plugin wires both backend services with a profile-refresh hook. Committed
successes (including replays) and uncertain storage failures invalidate the current
online snapshot and start a bounded async read. Offline players are not cached.
Each invalidation increments a revision while preserving the login token.
Only a matching `(uuid, token, revision)` can publish the read or its failure.
An older read cannot overwrite a newer read, invalidate newer ready data, or cross
a quit/rejoin boundary.

`/profile` shows loading while a refresh is pending and unavailable if it fails;
it never performs SQL or spends from cached balances. `/balance` independently
reads the authoritative coin total. Command replies run on the server thread and
are discarded if the initiating player disconnected/rejoined. A timeout does not
cancel an accepted mutation; logs retain the operation UUID for investigation.

## Lobby presentation

Session state changes enqueue dirty viewer UUIDs without using Bukkit off-thread.
One shared five-tick task handles display completions, animation and cached-stat
refreshes. Bossbar objects and sidebar rows are diffed in place; no database,
file or network IO occurs in this loop. Sidebar entries use stable internal
identities and Paper component score names, so duplicate/blank text rows work.

Preferences load/save asynchronously using V3. Displays stay hidden until the
saved flags are known. Settings-entry identity rejects completions after
quit/rejoin, while menus also check the profile login token and exact inventory.
ClubCore explicitly owns the lobby sidebar, with a suppression
API for future games and safe restoration on cleanup. See [displays.md](displays.md)
for ownership, command, reload and deployment contracts.

## Unified settings

`/display`, `/settings` and plugin callers use `PlayerSettingsService`; the
display manager has no second preference cache. Its compatibility toggle methods
delegate to this service. The service wraps `DisplayPreferenceService` and
`JdbcDisplayPreferenceRepository` without changing V3 or adding another worker.
Only one mutation per active player settings session is accepted at a time.

Saves retain confirmed choices while pending. Commit publishes a new immutable
snapshot; failure logs an uncertain outcome and starts an ordered authoritative
read before accepting another write. Caller timeouts/cancellation do not cancel
accepted persistence. Old-session work can complete in storage but cannot update
a replacement settings entry or menu.

Observers only mark displays dirty and enqueue main-thread menu refreshes.
There is no menu timer or animation-time SQL. The holder, player object, profile
token and current top inventory all have to match before deferred clicks or
callbacks can alter UI. Menus close before settings workers drain on disable.
See [settings.md](settings.md) and the [V3 reuse decision](decisions/0002-unified-player-settings.md).

## Error categories

| Situation | Signal | Player sees |
|---|---|---|
| Database unreachable or SQL error | `ProfileStorageException` | "Profiles are temporarily unavailable." |
| Load took too long | `TimeoutException` | same |
| Service shut down or queue full | `ProfileServiceClosedException` | same |
| Profile does not exist (`findById`) | `Optional.empty()` | n/a |
| Invalid input (e.g. 17-character name) | `IllegalArgumentException` | n/a (programmer error) |
| Invalid config or missing password | `ConfigException` → plugin disables itself at startup | n/a |

## Packaging

`./gradlew :core:build` produces `core/build/libs/ClubCore-<version>.jar`, a Shadow jar.

- The PostgreSQL JDBC driver, HikariCP and Flyway are bundled and relocated under `com.inpuzah.club.core.libs`, so they never clash with other plugins.
- SLF4J is excluded because Paper already provides it.
- Service files are merged; Flyway depends on this to find its core and PostgreSQL plugins.
