# ClubCore v0.1.0 — Foundation & `/profile` Implementation Specification

**Project:** Mineclub-inspired social Minecraft network (working codename: Club)
**Target:** Paper 26.3, Java 25, Gradle (Kotlin DSL), PostgreSQL
**Document status:** Implementation-ready checklist; application code not yet implemented
**Milestone:** 001 — Player Identity & Persistence

## 1. Goal and scope

Build the first reliable, extensible backend service for the network. On first join, players receive persistent UUID-keyed profiles. `/profile` displays profile data. After reconnect/restart, data remains intact. Profile operations use explicit services, not ad-hoc SQL calls throughout the plugin.

### Deliverables
- [ ] Reproducible Java 25 / Gradle project and Git repository.
- [ ] `ClubCore` plugin loads successfully on a separate Paper 26.3 test server.
- [ ] PostgreSQL database, restricted application credentials, schema migration system, and connection pool.
- [ ] Immutable or controlled-mutation profile domain model.
- [ ] Profile repository with create-or-load and update operations.
- [ ] Profile service for asynchronous loading and safe access.
- [ ] Player join/quit session lifecycle and graceful error states.
- [ ] `/profile` command (self; optional permission-controlled lookup later).
- [ ] Unit, integration, and manual acceptance tests.
- [ ] CI build, deployment checklist, configuration documentation, backups.

### Out of scope for v0.1
- Friends, parties, cosmetics, housing, minigames, shops, real currency transactions.
- Web login or Discord account linking.
- Cross-server synchronization, Redis, Velocity events, multi-server active sessions.
- Detailed GUI profile interface; start with chat output using Adventure components.
- Player-visible editing of balances or levels; these fields remain read-only until their authoritative services exist.

## 2. Setup and source control

- [ ] Install JDK 25 and verify `java -version` and `javac -version`.
- [ ] Install IntelliJ IDEA and configure Gradle's JVM to JDK 25.
- [ ] Create private GitHub repository `club-server`.
- [ ] Add `README.md`, `.gitignore`, `LICENSE` (if you plan to publish), `docs/`, `gradle/`, Gradle wrapper.
- [ ] Create separate local Paper 26.3 server, distinct from public lobby.
- [ ] Record exact Paper server build and Java version used in testing.
- [ ] Configure branch protection on `main` when collaborators are added.
- [ ] Pick a stable package root (e.g. `dev.yourbrand.club.core`; replace with a namespace you control).
- [ ] Establish commit conventions: `feat:`, `fix:`, `test:`, `docs:`, `chore:`.
- [ ] Exclude `.env`, secrets, local test-server folders, runtime databases, and generated jars from Git.

### Proposed initial repository layout

```text
club-server/
├── .github/workflows/build.yml
├── .gitignore
├── README.md
├── docs/
│   ├── architecture.md
│   ├── database.md
│   ├── configuration.md
│   ├── testing.md
│   └── decisions/0001-profile-persistence.md
├── gradle/wrapper/...
├── gradlew
├── gradlew.bat
├── settings.gradle.kts
├── build.gradle.kts
└── core/
    ├── build.gradle.kts
    └── src/
        ├── main/
        │   ├── java/dev/yourbrand/club/core/
        │   │   ├── ClubCorePlugin.java
        │   │   ├── command/ProfileCommand.java
        │   │   ├── listener/PlayerSessionListener.java
        │   │   ├── model/PlayerProfile.java
        │   │   ├── service/ProfileService.java
        │   │   ├── service/DefaultProfileService.java
        │   │   ├── repository/ProfileRepository.java
        │   │   ├── repository/JdbcProfileRepository.java
        │   │   ├── session/ProfileSessionManager.java
        │   │   ├── persistence/DatabaseManager.java
        │   │   └── config/DatabaseConfig.java
        │   └── resources/
        │       ├── plugin.yml
        │       ├── config.yml
        │       └── db/migration/V1__create_player_profiles.sql
        └── test/java/.../
```

**Scope discipline:** Begin with one `core` plugin module. Create separate `social`, `lobby`, and `games` modules only when real code requires them. A future `club-api` module may expose *only* safe public interfaces without DB implementation or internal classes.

## 3. Gradle and plugin registration

- [ ] Initialize `settings.gradle.kts`, including `rootProject.name = "club-server"` and `include("core")`.
- [ ] Add `java` plugin to `core/build.gradle.kts`.
- [ ] Add PaperMC Maven repository and `compileOnly("io.papermc.paper:paper-api:26.3.build.+")` for initial setup; later pin a tested build for reproducible releases.
- [ ] Configure Java toolchain `JavaLanguageVersion.of(25)`.
- [ ] Configure sources encoding UTF-8, JUnit 5 and `useJUnitPlatform()`.
- [ ] Add PostgreSQL JDBC driver, HikariCP pool, and Flyway migration dependencies with checked stable versions.
- [ ] Build a *shaded/relocated* deployable jar containing JDBC, HikariCP and Flyway if not supplied by server; verify compatibility and avoid exporting these classes to other plugins. Use Shadow plugin and test final jar.
- [ ] Add `plugin.yml` with plugin name, version, main class, `api-version: '26.3'`, and `/profile` registration. Prefer `plugin.yml` for the simple initial command.
- [ ] Bundle a commented `config.yml` with no real credentials.
- [ ] Run `./gradlew :core:build` and verify compiled jar contents.
- [ ] Put the jar in the test server's `plugins/` directory, start server, and check `/plugins` and startup logs.

### Minimal `plugin.yml` specification

```yaml
name: ClubCore
version: '0.1.0'
main: dev.yourbrand.club.core.ClubCorePlugin
api-version: '26.3'
description: Core player identity and persistence services
commands:
  profile:
    description: Display your Club profile
    usage: /profile
    permission: club.profile.view
permissions:
  club.profile.view:
    description: View your profile
    default: true
```

The `main` class must match the Java package and class you actually write. For v0.1, `/profile` works for players; console returns a helpful message rather than throwing an error.

## 4. PostgreSQL provisioning and security

- [ ] Install PostgreSQL locally or launch it via Docker Compose.
- [ ] Create a dedicated database `club_dev` and least-privilege application role `club_app`.
- [ ] Create separate admin/migration credentials if running migrations independently; do not give website or players database credentials.
- [ ] Keep Postgres bound to localhost/private Docker network, **not** publicly exposed.
- [ ] Choose environment separation (`club_dev`, `club_stage`, `club_prod`) with independent credentials.
- [ ] Use config/environment secrets rather than putting passwords in source control.
- [ ] Define connection URL, username, password, pool max size, timeouts, and SSL mode according to deployment.
- [ ] Configure HikariCP with a small pool (e.g. 3–5 connections for a single small Paper server), bounded connection timeout, and leak detection only if debugging.
- [ ] Implement migrations using Flyway (or another migration tool, but commit to exactly one).
- [ ] Run first migration before accepting profile operations.
- [ ] Fail plugin enable clearly when migrations or DB connection checks fail; do not create fake successful profiles in memory.
- [ ] Prepare DB backup/restore procedure; test restore to an isolated development database.

### Initial schema: `V1__create_player_profiles.sql`

```sql
CREATE TABLE player_profiles (
    uuid UUID PRIMARY KEY,
    username VARCHAR(16) NOT NULL,
    first_joined_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    level INTEGER NOT NULL DEFAULT 1 CHECK (level >= 1),
    experience BIGINT NOT NULL DEFAULT 0 CHECK (experience >= 0),
    coins BIGINT NOT NULL DEFAULT 0 CHECK (coins >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

**Design decisions:** UUID is canonical identity; usernames may change and must never be used as keys. `last_seen_at` means last confirmed activity/session date as defined by your implementation (initially update on join and quit); `created_at` and `updated_at` are audit-related. The displayed level/XP/coins are placeholders reserved for their future authoritative services; do not allow arbitrary public writes. Use server-side UTC timestamps and format for display. Keep application-managed `updated_at` consistent on every update.

### Upsert behavior
- [ ] On join, upsert `uuid`, current `username` and `last_seen_at`.
- [ ] Never reset coins, XP, level or `first_joined_at` on conflict.
- [ ] Return the resulting row as the profile snapshot.
- [ ] Write a repository test proving username changes do not create new players.

Example intent (adapt to Java repository layer):

```sql
INSERT INTO player_profiles (uuid, username)
VALUES (?, ?)
ON CONFLICT (uuid)
DO UPDATE SET username = EXCLUDED.username,
              last_seen_at = NOW(),
              updated_at = NOW()
RETURNING *;
```

## 5. Domain model and services

- [ ] Define `PlayerProfile` with UUID, current username, first join, last seen, level, XP, coins.
- [ ] Define explicit accessors and validated mutation methods (or an immutable record plus returned updated snapshots).
- [ ] Define `ProfileRepository` for persistence, independent of Paper/Bukkit types.
- [ ] Define `ProfileService` as the only supported application entry point for commands and future modules.
- [ ] Define `ProfileSessionManager` for current online player profile state; key by UUID.
- [ ] Choose well-defined states: `LOADING`, `READY`, `UNAVAILABLE`, `CLOSED`.
- [ ] Load profiles asynchronously on join and publish completed result back on main thread for Paper-facing actions.
- [ ] Ensure repeated `/profile` calls during load report “Your profile is still loading” instead of issuing unbounded DB requests.
- [ ] Ensure stale async results are dropped if player disconnected or a newer session replaced them (session token/generation).
- [ ] On quit, remove the active session, update last-seen asynchronously (or use a bounded write queue) and capture/log failures.
- [ ] Never call Bukkit world/player APIs from a DB worker; only operate on copied UUID/username and plain model data there.
- [ ] Do not use Bukkit's main thread for SQL queries, network I/O or blocking `Future.get()/join()`.
- [ ] On plugin shutdown, stop accepting new operations, wait a bounded period for pending writes, then close the pool; log unfinished writes clearly.
- [ ] Make failure handling explicit: distinguish unavailable database, missing profile, invalid state and programmer errors.

### Example service boundary

```java
public interface ProfileService {
    CompletableFuture<PlayerProfile> getOrCreate(UUID uuid, String username);
    CompletableFuture<Optional<PlayerProfile>> findById(UUID uuid);
}
```

Keep this as a conceptual API until implementation. The main-thread handler must use non-blocking completion callbacks and schedule all player messaging on the server thread.

## 6. Event lifecycle

### First join
1. `PlayerJoinEvent` captures UUID and current username on the Paper thread.
2. Session manager creates a `LOADING` session with unique generation/token.
3. Repository runs an upsert asynchronously using a pooled connection.
4. Result is returned to Paper main thread for safe event/player interaction.
5. If player remains online and token matches, mark session `READY` and optionally show a subtle ready message.
6. On failure mark session `UNAVAILABLE`, log root cause, and show player a generic failure message (no credentials/SQL details).

### Returning player
- Same path as first join; username and last seen update, existing level/XP/coins remain unchanged.

### Quit
- Invalidate session so late callbacks cannot affect a different player session.
- Persist last-seen timestamp and remove session from memory.
- Log write failures and close gracefully on server stop.

### Server crash limitation
- `PlayerQuitEvent` may not fire on a crash. Therefore `last_seen_at` should be treated as a best-effort timestamp, not an exact disconnect record. Later add periodic heartbeats if precise presence reporting is needed.

## 7. `/profile` command specification

**Syntax:** `/profile` (self only in v0.1). The command must be registered in `plugin.yml` and available to players with `club.profile.view`.

### Expected happy path

```text
──────── Club Profile ────────
Player: Inpuzah
Level: 1
Experience: 0 XP
Coins: 0
First Joined: 2026-11-06
──────────────────────────────
```

Use Kyori Adventure components for text and colors. Keep labels configurable later. Avoid storing presentation/color codes in database.

### Required behavior
- [ ] Player with loaded profile sees UUID-associated data.
- [ ] First join displays level=1, experience=0, coins=0.
- [ ] Rejoin displays unchanged values.
- [ ] Data comes from `ProfileService`/session state, not direct SQL in command handler.
- [ ] Console execution prints “This command can only be used by a player.”
- [ ] Missing permission is denied clearly by command framework.
- [ ] While loading: “Your profile is still loading. Try again shortly.”
- [ ] During DB failure: “Profiles are temporarily unavailable.”
- [ ] Wrong/extra arguments show `/profile` usage (rather than silently ignoring input).
- [ ] No unhandled exception, main-thread database access, or sensitive server details in player-facing text.

## 8. Configuration and operational behavior

### Example `config.yml` (non-secret sample)

```yaml
database:
  host: "127.0.0.1"
  port: 5432
  name: "club_dev"
  username: "club_app"
  # Read password from CLUB_DB_PASSWORD environment variable
  pool-size: 4
  connection-timeout-ms: 5000
profile:
  load-timeout-ms: 10000
  show-ready-message: false
```

- [ ] Define documented default config values.
- [ ] Environment variable missing? Fail with an explicit startup error.
- [ ] Validate all config types/ranges on startup.
- [ ] Do not log passwords or raw connection strings containing secrets.
- [ ] Log plugin version, schema migration result, and profile service ready state.
- [ ] Document startup/shutdown handling, useful log identifiers, and troubleshooting steps.

## 9. Testing plan

### Unit tests (no server required)
- [ ] New profile has level 1, XP 0, coins 0.
- [ ] No negative balances/XP, level cannot be < 1.
- [ ] UUID identity unaffected by username changes.
- [ ] Service handles missing records and DB exceptions distinctly.
- [ ] Session tokens reject stale callbacks.

### Repository integration tests (real Postgres, ideally Testcontainers)
- [ ] Migrations apply to fresh database.
- [ ] First upsert creates exactly one row.
- [ ] Second upsert updates name without resetting first join, level, XP, coins.
- [ ] Concurrent upserts for same UUID do not create duplicates.
- [ ] Persistence survives process restart.
- [ ] Missing DB connection fails gracefully.
- [ ] Non-ASCII/valid Minecraft names and max 16-character username handled according to Mojang's Java username rules.
- [ ] Timestamps are stored with timezone-aware types.

### Manual Paper server tests
- [ ] Run server with ClubCore installed; plugin enabled without warnings/errors.
- [ ] First join creates a DB row.
- [ ] `/profile` shows expected text.
- [ ] Rejoin and server restart preserve profile.
- [ ] Execute command from console; no crash.
- [ ] Disable DB, join server, run `/profile`; friendly error, no long tick lag.
- [ ] Restore DB connection and restart; behavior normal.
- [ ] Join, disconnect rapidly, and rejoin; no stale session messages.
- [ ] Test at least two different UUIDs; separate profile data.
- [ ] Review logs for stack traces and secret leakage.

### Performance and reliability
- [ ] All SQL operations off main thread.
- [ ] A DB outage must not freeze tick processing.
- [ ] Profile load coalescing avoids duplicate requests from one player.
- [ ] Pool and async executor use bounded capacity.
- [ ] Server shutdown has bounded wait, documented rollback/failure behavior.

## 10. Delivery, release and deployment

- [ ] Add GitHub Actions to run JDK 25 Gradle build and tests on push/PR.
- [ ] Archive deployable shaded jar as workflow artifact.
- [ ] Record dependency versions and maintain a lockfile/version catalog if appropriate.
- [ ] Create staging Postgres credentials and backups separately from local development.
- [ ] Deploy `ClubCore-0.1.0.jar` to staging Paper 26.3.
- [ ] Run complete manual checklist on staging.
- [ ] Tag release `v0.1.0` with changelog and tested Paper/Java versions.
- [ ] Backup lobby world and database before production deployment.
- [ ] Deploy only during a maintenance window with a tested rollback plan.
- [ ] Confirm no unexpected plugin conflicts.

## 11. Definition of Done

Milestone 001 is **complete** only when **all** of the following are true:

1. Build reproducibly passes from clean checkout (`./gradlew clean build`).
2. ClubCore loads on Paper 26.3 with Java 25 and its command is registered.
3. First join creates a single UUID-keyed PostgreSQL record.
4. `/profile` shows the correct record for the current player.
5. Username changes, reconnects and server restart do not reset profile fields.
6. DB work never blocks the Paper main thread.
7. Load failures, quit-during-load and DB outage are handled without corrupting sessions or crashing the server.
8. Automated model/repository tests and manual staging tests pass.
9. Schema migration and credential handling are documented.
10. Code, docs, migrations, tests, and release tag are pushed to GitHub.

## 12. Future milestone contract

**ClubCore v0.2:** EconomyService with atomic balance changes, transaction ledger and idempotency keys; do NOT implement balance modifications via arbitrary `profile.setCoins()` writes across plugins.

**ClubCore v0.3:** ProgressionService with XP events, level curves, audit trails and limits.

**ClubSocial:** Friend relations, party membership, interactions; use UUIDs and ProfileService.

**ClubCosmetics:** Cosmetic catalog and per-player ownership tables; avoid embedding inventories into `player_profiles`.

**ClubGames:** Match service and per-game stats tables; use core identity and independent reward services.

**ClubWeb:** Authorized HTTP API/service; do not make database or secrets accessible from the browser.

**Network expansion:** Introduce Velocity and optionally Redis when cross-server events, online presence and cache invalidation are actually required. Plan for multi-server session consistency before enabling multiple Paper servers to mutate the same profile simultaneously.

## 13. Suggested GitHub issue breakdown

- `CORE-001` — Bootstrap JDK25/Gradle/Paper module and test server
- `CORE-002` — Build plugin entry point, metadata and `/profile` stub
- `CORE-003` — Provision local PostgreSQL and secret configuration
- `CORE-004` — Add Flyway V1 migration and DB pool
- `CORE-005` — Implement PlayerProfile model and repository
- `CORE-006` — Implement ProfileService + session states
- `CORE-007` — Wire join/quit lifecycle and async safety
- `CORE-008` — Display Adventure-formatted `/profile`
- `CORE-009` — Implement error paths and shutdown handling
- `CORE-010` — Unit/integration tests and staging tests
- `CORE-011` — GitHub Actions and tagged v0.1.0 release

Recommended order: `001 → 002 → 003 → 004 → 005 → 006 → 007 → 008 → 009 → 010 → 011`.

## 14. Official development references

- Paper project setup: https://docs.papermc.io/paper/dev/project-setup/
- Paper plugin.yml: https://docs.papermc.io/paper/dev/plugin-yml/
- Paper scheduler: https://docs.papermc.io/paper/dev/scheduler/
- Paper development overview: https://docs.papermc.io/paper/dev/
- Paper 26.3 JavaDoc: https://jd.papermc.io/paper/26.3/

**Caution:** This document describes an implementation plan, not a compiled working plugin. Pin current tested dependency versions before release. Use your own project name and owned assets rather than relying on Mineclub trademarks or proprietary server assets.
