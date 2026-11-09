# Architecture

ClubCore v0.1 provides player identity and persistence for a single Paper 26.3 server.

## Layers

```text
PlayerSessionListener / ProfileCommand        (server thread; Bukkit API)
        │                    │
        ▼                    ▼
  ProfileService      ProfileSessionManager    (async API / in-memory session state)
        │
        ▼
  ProfileRepository → JdbcProfileRepository    (blocking JDBC; no Bukkit types)
        │
        ▼
  DatabaseManager (HikariCP) + Migrations (Flyway) → PostgreSQL
```

| Package | Responsibility |
|---|---|
| `config` | Load and validate `config.yml`; prefer `CLUB_DB_PASSWORD`, falling back to runtime `database.password`. |
| `model` | `PlayerProfile` immutable record with validation. |
| `persistence` | Connection pool and schema migrations. |
| `repository` | SQL only. Throws `ProfileStorageException` when the database fails. |
| `service` | `ProfileService`, the only supported API for profile data. Runs work on a bounded worker pool and merges duplicate loads. |
| `session` | `ProfileSessionManager` tracks `LOADING → READY / UNAVAILABLE → CLOSED` per online player. |
| `listener` | Join/quit lifecycle. |
| `command` | `/profile`. Reads session state only and never queries the database. |

## Threading rules

- No SQL on the server thread. Every repository call runs inside `DefaultProfileService`'s worker pool.
- That pool has `pool-size` threads and a queue capacity of 1,000. When the queue is full, the operation fails with `ProfileServiceClosedException` instead of queueing without limit.
- Results return to the server thread through `BukkitScheduler#runTask` before any Bukkit API is touched. Results that arrive after the plugin is disabled are dropped.
- Worker threads only handle plain values (UUID, username, `PlayerProfile`), never `Player` objects.

## Session lifecycle

1. **Join** (`PlayerJoinEvent`, MONITOR): `sessions.begin(uuid)` creates a `LOADING` session with a unique token. Then `profiles.getOrCreate(uuid, name)` runs with a timeout of `profile.load-timeout-ms`.
2. **Load completes** (server thread):
   - `markReady(uuid, token, profile)` applies only if that session is still current. Otherwise the result is stale (the player quit or rejoined) and is dropped.
   - On failure or timeout, the session becomes `UNAVAILABLE`. The root cause is logged, and the player sees only "Profiles are temporarily unavailable."
3. **Quit**: the session is removed, so late callbacks are ignored, and `last_seen_at` is updated asynchronously. Write failures are logged.
4. **Shutdown**: all sessions are closed and a last-seen write is queued for each online player. The service stops accepting work and waits up to 10 s for queued writes, logs how many were abandoned, and then closes the pool.
5. **Reload**: players who are already online when the plugin enables get a new session.

`last_seen_at` is best-effort: `PlayerQuitEvent` does not fire if the server crashes.

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
