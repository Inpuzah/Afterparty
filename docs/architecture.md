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
| `social` | v0.4 development: transactional friendship/block/privacy repository, bounded async service and paged player commands. |

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

The [social development slice](social.md) uses explicit async reads on social
actions, with no global friend-graph cache or display-loop database polling.
Pair writers lock profile rows in PostgreSQL UUID order before checking blocks,
privacy, rate/inbox limits and relationship state. Privacy writers lock the same
owner profile. One transaction owns each relationship change; the service is
drained before the database closes. Presence and command replies run only on the
Paper thread through `CommandFeedback`. Social Privacy is a separate settings
submenu using the same bounded social preference service, V4 friend privacy and
V5 party-invite privacy. Both choices are returned as one immutable snapshot under
the owner's profile lock; each setter writes only its own flag and reuses the
existing party preference persistence path. It retains
confirmed choices while saving and orders reads after pending UI writes across
close/rejoin. Exact holder, player object and login checks protect every callback.
Sneak-right-click opens a holder-bound interaction menu with cached target profile
data and an explicit asynchronous pair relationship read. The read uses the same
ordered profile locks as writers for a coherent snapshot, without exposing the
other player's block. Clicks submit the explicit action that was rendered, never
a recomputed toggle; transactional mutations revalidate current state. Accepted
menu writes are retained across close/rejoin, and subsequent menu reads wait for
them. Viewer/target login checks, deferred actions, inventory movement cancellation
and quit/disable cleanup reject stale UI. There is no per-menu repeating task.

The [party development backend](parties.md#transactional-repository-and-async-service)
adds V5 and a Bukkit-free repository/service. A dedicated direct PostgreSQL lease
owns the server epoch. Repository calls verify it and lock the active epoch row
before ordered profile locks, conservatively serializing this server's party
operations and fencing closure. Block/privacy checks share V4 profile locks.
Auto-create/invite and acceptance are transactional; immutable snapshots and
revisioned mutations are exposed through bounded async workers. This subsystem
has startup-validated opt-in configuration, personal privacy and an asynchronous
`PartyRuntime`. The runtime acquires matching application/direct endpoint ownership
before publishing its service, checks lease health off-thread every five seconds
and before work, and rejects new/queued work after failure or stop. It never
reacquires automatically. Cleanup drains workers before epoch close; if futures
are abandoned at the deadline, ownership stays held until actual workers exit.
Plugin shutdown bounds its wait and logs pending/failed cleanup before closing
the shared pool. The staged holder-bound party overview menu uses the bounded
service for reads/mutations, preserves pending writes across close/rejoin and
requires exact party ID/revision inside leave/disband transactions. Controls are
registered on the server thread after opt-in backend startup. One FIFO party
worker orders older accepted writes before grace cleanup; the shared one-second
server sweep submits at most 16 departures per pass/outstanding. An atomic
departure claim under transaction locks competes with reconnect cancellation.
If cleanup claimed first, a new login's party admission waits on its result,
without blocking Paper or affecting profile readiness. Errors stop parties
rather than blindly retrying uncertain removal. Candidate and incoming-invitation
inventories preserve exact target login tokens/invite UUIDs and pending writes.
Commands and interaction/candidate menus share one invitation entry point and
original-recipient-session notifications. The FIFO worker captures committed
membership changes for main-thread join/leave/kick/leadership/closure notices.
No speculative menu or rejected mutation emits a membership notice.

Local `/pc` delivery uses `withParty`: the worker holds the epoch/profile transaction
locks while a main-thread callback revalidates sender/recipients and applies the
cancellable `PartyChatEvent`. No SQL runs on Paper. Committed removals/disbands
cannot overtake delivery. A callback not started within two seconds is cancelled
before the lock is released; once started, ownership remains held until it finishes.
Moderation listeners must not synchronously wait for party futures or perform
blocking I/O. There is no chat history, global broadcast or content persistence.
Permissions, explicit mute denial, single pending/player and rate/length/control
limits apply before submission and at delivery. An external moderator must wire
its own global mute policy to the event/denial permission.

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
