# 0001 — Profile persistence

- **Status:** Accepted
- **Date:** 2026-11-06

## Context

The network needs persistent player identity that later services (economy, progression, social, cosmetics) can build on. Paper's server thread must never block on I/O, and players can rename their accounts at any time.

## Decision

1. **PostgreSQL**, with UUID as the primary key. Usernames are stored only as display data.
2. **One upsert on join** (`INSERT ... ON CONFLICT DO UPDATE ... RETURNING`). It is atomic, so concurrent joins can't create duplicates, and it only touches `username`, `last_seen_at` and `updated_at`.
3. **Flyway** is the single migration tool and runs automatically at startup. Migrations are immutable once applied.
4. **HikariCP** with a small pool (default 4). Startup fails fast if the database is unreachable, rather than creating fake in-memory profiles.
5. **Layered access:** command and listener → `ProfileService` (async, bounded, merges duplicate loads) → `ProfileRepository` (blocking JDBC, no Bukkit types). Other plugins must use `ProfileService` and never query the database directly.
6. **Session tokens:** `ProfileSessionManager` gives every join a unique token, so results from an earlier session are discarded.
7. **Load on `PlayerJoinEvent`, not before login:** players are never kicked because of a database outage. Profile features simply show as unavailable.
8. **Shaded, relocated dependencies** (Shadow) so the plugin is self-contained and its libraries can't conflict with other plugins.
9. Level, experience and coins are stored but **read-only**. Future `EconomyService` and `ProgressionService` will own all writes, with ledgers and audit trails.

## Consequences

- `last_seen_at` is best-effort. It isn't updated if the server crashes, and pending writes are abandoned after 10 s on shutdown.
- During an outage, players can still play, but profile-dependent features are unavailable for the rest of that session; they reload on the next join.
- This design assumes **one server writes each profile**. Before several Paper servers share the database, add cross-server session ownership (e.g. Velocity plus Redis) so two servers can't update the same profile at once.
