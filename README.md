# Club Server

Backend plugins for the Club Minecraft network.

**Current milestone:** ClubCore v0.5 development (staging validation pending) - Parties and Communication<br>
**Target:** Paper 26.3, Java 25, Gradle Kotlin DSL, PostgreSQL  
**Status updated:** November 9, 2026

ClubCore is the foundation for future profiles, currencies, progression, settings,
and persistent network data. Only the `core` plugin exists today; the full network
and its future systems are not implemented.

## What we have so far

| Area | Implemented |
|---|---|
| Plugin bootstrap | Main class, `plugin.yml`, configuration, profile/XP/coin service lifecycle and command registration. |
| Player identity | Immutable UUID-keyed profile with username, first join, last seen, level, XP, and coins. |
| Persistence | PostgreSQL JDBC repositories, HikariCP connection pool, Flyway V1-V4 plus V5 party storage on this development branch. |
| XP backend | Atomic lifetime XP awards, level-100 cap, ledger, idempotent retries and admin grants. |
| Coins backend | Atomic credits/debits, 1-billion balance cap, overdraft checks, ledger and admin commands. |
| Lobby displays | Animated bossbar/sidebar, cached placeholders, persistent visibility flags and validated admin reload. |
| Player settings | Immutable async settings API and persistent `/settings` inventory GUI, sharing V3 choices with `/display`. |
| Join/quit lifecycle | Async profile creation/loading on join and last-seen updates on quit. |
| Parties (opt-in) | Transactional ephemeral parties, reconnect grace, personal invite privacy, commands, overview/invitation GUIs, interaction invites, committed member notices and moderated/rate-limited local `/pc`. Live staging acceptance is outstanding. |
| Session state | `LOADING`, `READY`, `UNAVAILABLE`, and `CLOSED`, with login tokens and refresh revisions to reject stale callbacks. |
| Profile service | Async API, duplicate-load coalescing, bounded worker queue, load timeouts. |
| Failure handling | Friendly player messages; invalid configuration or database/migration failure disables the plugin at startup. |
| Packaging | Self-contained Shadow jar with relocated PostgreSQL, HikariCP, and Flyway dependencies. |
| Tests | 483 automated tests, including 181 party domain/storage/service/command/configuration/runtime/menu/disconnect/communication tests and real embedded PostgreSQL; the merged v0.4 baseline had 291. |
| Operations | Local Docker Compose database, provisioning script, configuration and backup documentation. |
| CI | GitHub Actions workflow for Java 25 build/tests and jar upload; v0.3 passed remotely, social branch verification pending. |
| Neon | CLI/project setup and environment pull completed; Java integration remains unfinished. |

Level starts at **1**, experience at **0**, and coins at **0**. Admin commands can
now update progression and currency; ordinary activity rewards are not implemented.
Username changes do not reset UUID identity, first-join date, or progression fields.

## Commands

| Command | Permission | Behavior |
|---|---|---|
| `/profile` | `club.profile.view` (default: everyone) | Shows your own name, level, XP, coins, and first-join date in chat. |
| `/balance` | `club.balance.view` (default: everyone) | Player-only, asynchronous saved Club Coins balance. |
| `/clubadmin xp add <online-player\|uuid> <amount> [reason]` | `club.admin.xp` (default: op) | Grant lifetime XP to an existing profile. |
| `/clubadmin coins add <online-player\|uuid> <amount> [reason]` | `club.admin.coins` (default: op) | Credit Club Coins up to the balance cap. |
| `/clubadmin coins remove <online-player\|uuid> <amount> [reason]` | `club.admin.coins` (default: op) | Debit Club Coins without overdrawing. |
| `/display status` | `club.display` (default: everyone) | Show saved display preferences and server enable flags. |
| `/display bossbar on\|off` | `club.display` (default: everyone) | Persist your bossbar visibility. |
| `/display scoreboard on\|off` | `club.display` (default: everyone) | Persist your sidebar visibility. |
| `/settings` | `club.settings` (default: everyone) | Open your display settings menu; left/right-click to persist a toggle. |
| `/party [action]` | `club.party.use` (default: everyone) | Opt-in local parties, overview GUI and invitations; see [party commands](docs/parties.md#party-command-adapter). Disabled until `party.enabled` and backend readiness. |
| `/clubadmin displays reload` | `club.admin.displays` (default: op) | Validate/reload display settings without changing database settings. |

The v0.4 development branch also implements `/friend`, `/friends`, `/block`,
`/unblock` and `/blocked` under `club.social.use` (default everyone).
See [friends development slice](docs/social.md) for syntax, persistence and
privacy submenu and sneak-right-click interaction GUI; these are not staging-approved features.

- `/profile` is players only; console receives a helpful message.
- Extra arguments show `Usage: /profile`; lookup of other players is not supported.
- During loading: "Your profile is still loading. Try again shortly."
- During failure: "Profiles are temporarily unavailable."
- The command reads cached session state and never queries the database directly.
- Admin commands support console, accept online names or persisted full UUIDs,
  and log a new operation ID with actor/reason metadata for each invocation.
- Admin amounts have **no per-command limit by default**, as requested. Optional
  `admin-commands.max-xp` / `max-coins` limits use 0 for unlimited; amounts must
  still be positive whole numbers, and domain cap/overflow/overdraft rules apply.
- Replies follow the database result on the server thread. Online profile caches
  refresh after success; `/profile` shows loading/unavailable instead of old data.
- A timeout may hide a committed operation. Check its logged ID before repeating
  an admin command, because each invocation generates a new ID.
- Displays use one shared animation task and cached profile data. ClubCore owns
  the lobby sidebar; future games can explicitly suppress it before taking ownership.

## Project layout

```text
core/
  src/main/java/com/inpuzah/club/core/
    ClubCorePlugin.java
    command/       Profile, balance and permission-controlled admin commands
    config/        Startup validation and secret lookup
    display/       Shared animation, sidebar/bossbar lifecycle, persistent preferences
    listener/      Player join/quit events
    model/         Immutable profile snapshot
    economy/       Atomic coin repository and async service
    persistence/   Connection pool and migrations
    progression/   Level calculation, XP repository and async service
    repository/    Blocking JDBC operations, no Bukkit types
    service/       Async profile API, revision-safe refreshes and bounded workers
    session/       Online session states, login tokens and read revisions
    settings/      Unified persistent settings API and safe inventory menu
  src/main/resources/
    plugin.yml
    config.yml
    db/migration/V1__create_player_profiles.sql
    db/migration/V2__progression_and_economy.sql
    db/migration/V3__display_preferences.sql
  src/test/java/   Unit and PostgreSQL integration tests
docs/             Architecture, database, configuration, testing, decisions
docker/           Local database initialization
gradle/           Wrapper and pinned dependency versions
.github/workflows/build.yml
neon.ts           Neon infrastructure configuration, not a plugin runtime
```

Only `core` is included in the Gradle build. New modules should be added when they
have real implementations.

### v0.2 baseline and v0.3 candidate

The merged v0.2 baseline adds progression/economy and display work to the
v0.1 foundation. It includes the
**V2 coin/XP ledger schema** and
**atomic XP and coin backend APIs**, with migration, concurrency, replay and
rollback tests. Levels cap at 100 while lifetime XP continues accumulating.
Coin credits enforce a **1,000,000,000-coin balance cap**; debits reject overdrafts.
Existing V1 profile data is preserved.

Both services are bootstrapped, exposed through plugin accessors and drained before
the database closes. `/balance`, admin grants/removals and ordered cache refreshes
are wired. Lobby displays add animated bossbar/sidebar displays,
V3 persistent preferences, cached placeholders and display reload.
Activity reward hooks remain deferred.

The v0.3 candidate adds a shared `PlayerSettingsService` and `/settings` GUI.
It reuses the V3 table unchanged: no V4, reset of existing choices, or separate
GUI persistence path. Both `/display` and the menu update only confirmed cached
choices after persistence. See [settings](docs/settings.md) and
[v0.3 release notes](docs/release-notes-0.3.0.md).

See the [v0.2 release notes](docs/release-notes-0.2.0.md) for the combined scope,
upgrade procedure, verification evidence and outstanding acceptance checks.

The plugin applies V2/V3 automatically on startup when deployed. Staging database
confirmation, backup/restore and real-client acceptance checks remain required
before deployment. No live database was migrated during development. See
[progression and economy](docs/progression-and-economy.md) for details.

## Requirements and build

### v0.5 parties groundwork

The `feat/parties` branch starts from merged social main `0fe22b9`. It adds
validated immutable party/invite/privacy domain models and
[confirmed party rules and implementation stages](docs/parties.md).
V5 adds party storage, epoch ownership and independent invite privacy, with
PostgreSQL-tested lease/reconciliation and privacy repositories. Transactional
party mutations and a bounded async API now enforce membership, leadership,
block/privacy and invite abuse limits. The party command and overview menu are
registered after opt-in backend readiness, with paged members and guarded
create/leave/disband. Disconnect grace defaults to five minutes, with cancellation
of queued expiry on rejoin and a nonblocking reconnect fence for already claimed
cleanup. Paged candidate/invitation inventories, interaction invites, committed
member notifications and local `/pc` with moderation/rate/session safeguards are
implemented. Client staging, moderation integration, remote CI and operational
release gates remain outstanding.
Startup-validated party configuration defaults off;
enabling it starts the backend asynchronously with dedicated epoch ownership,
lease-loss containment and ordered shutdown, then activates player controls.
The Social Privacy submenu now persists independent Everyone/Nobody controls for
friend requests and party invitations, including when party runtime is disabled.
V1-V4 are unchanged, but startup **will apply V5**; this snapshot is not
production-ready.

### v0.4 development branch

This branch starts the friends milestone on top of merged v0.3. It adds
transactional requests/friendships/blocks, a bounded async service, paged commands
and V4 social tables. It does not modify V1-V3 or the existing display engine.
The Social Privacy submenu in `/settings` now persists Everyone/Nobody request
policy separately from display preferences. Sneak-right-click opens the player
interaction menu with cached profile previews, friend actions and block/unblock.
The v0.3 real-client and backup/restore gates remain open; see
[social implementation status](docs/social.md).

Local Java 25 verification passed: **291 tests, zero failures/errors/skips**.
The merged v0.4 jar contains four migrations and the five social commands;
the existing display engine and historical migrations are unchanged.

- **Java 25** to run the Paper server and compile the plugin.
- JDK 17+ can launch Gradle; the Foojay resolver can provision the Java 25 toolchain.
- A reachable PostgreSQL database for the plugin. Local setup and tests use PostgreSQL 18.
- Internet access for the first dependency/toolchain download.
- Docker is optional for local database setup; automated tests do not require it.
- Node.js/npm are only needed for Neon tooling, not for the Minecraft plugin.

Set `JAVA_HOME` to a valid installed JDK if the Gradle wrapper reports an invalid directory.

On Windows:

```powershell
.\gradlew.bat clean build
.\gradlew.bat :core:test
```

On macOS/Linux:

```sh
sh gradlew clean build
sh gradlew :core:test
```

Development output: **`core/build/libs/ClubCore-0.5.0-SNAPSHOT.jar`**.
Starting this jar automatically applies V1-V5. Rehearse migration and restore on
an isolated staging database first; this is not a production-approved release.
The Shadow jar bundles the database libraries; Paper supplies its API and SLF4J.
Dependency versions are pinned in [gradle/libs.versions.toml](gradle/libs.versions.toml),
including Paper API `26.3.build.244-stable`.

This is a Java/Gradle application. There is currently **no `npm run build` script**.

## Local database and test-server setup

1. Copy the example environment file and replace its placeholder passwords:

   ```powershell
   Copy-Item .env.example .env
   ```

2. Start the local database:

   ```powershell
   docker compose up -d
   ```

   Compose creates `club_dev` and the restricted `club_app` role on a new volume.
   Port 5432 is bound to localhost. Initialization scripts only run on the first
   creation of the volume. For non-Docker setup, see [database provisioning](docs/database.md).

3. Build the plugin and copy its jar into a **separate Paper 26.3 test server's**
   `plugins` directory. Use Java 25 for that server.

4. Set the app password in the environment of the process launching Paper:

   ```powershell
   $env:CLUB_DB_PASSWORD = '<your application-role password>'
   java -Xmx2G -jar paper.jar --nogui
   ```

   Docker Compose reads `.env`, but the Java process **does not automatically load**
   `.env` or `.env.local`. Set the variable explicitly in your shell or service.

5. On first start, ClubCore creates `plugins/ClubCore/config.yml`. Update its
   host, port, database name, username, and SSL mode if necessary, then restart.
   The defaults match the local Compose database. Alternatively, add
   `database.password` to the server's runtime YAML file. A non-empty
   `CLUB_DB_PASSWORD` takes precedence. Never put real passwords in the
   repository's bundled sample configuration.

6. Look for `Schema migration complete` and `Profile, progression and economy services ready` in the log.
   Join and run `/profile`, then reconnect and verify the persisted record.

Flyway runs before profile operations are enabled. Never edit an applied migration;
add a new versioned migration instead.

## Neon: configured, not wired into Java yet

The previous setup linked an existing Neon project/production branch, ran
`neon deploy`, and pulled connection variables into the git-ignored `.env.local`.
Neon agent skills and MCP tooling were also installed at user scope.

**Important:** ClubCore currently reads database host/name/username from
`config.yml` and prefers `CLUB_DB_PASSWORD`, falling back to `database.password`
in the server's runtime config. It does **not** consume
`DATABASE_URL` or `DATABASE_URL_UNPOOLED`. Its migrations and repository currently
share the same Hikari data source. Pulling Neon variables alone does not connect
the plugin to Neon.

The next integration should preserve JDBC/HikariCP, use the pooled URL for normal
profile traffic, and a separate direct URL for Flyway. Test this on a development
branch before migrating production. Never commit or paste connection strings.

## Verification so far

The v0.3 candidate clean build passed with **209 tests, zero failures/errors
and zero skipped tests**. Jar metadata confirms version 0.3.0, `/settings`,
V1/V2/V3 and all 42 merged Flyway service providers. Candidate checksum and
verification details are in the [v0.3 release notes](docs/release-notes-0.3.0.md).

The earlier November 9 v0.2 clean Gradle build passed with **187 tests, zero failures/errors,
and zero skipped tests**, including 25 display tests, command tests and
PostgreSQL-backed service/cache integration coverage. The shaded
v0.2 jar contained both backends, display services/commands and V1/V2/V3
migrations. The v0.3 candidate additionally tests shared settings, menu event
guards, duplicate-click protection and PostgreSQL upgrade/restart persistence.
Live Paper rendering is not yet verified; see the
[settings acceptance record](docs/testing/settings-manual-acceptance.md).

Automated coverage includes:

- Profile defaults, validation, and UUID identity across username changes.
- Session transitions, quit/rejoin token checks, and stale-result rejection.
- Async load coalescing, caller timeouts, queue rejection, and shutdown behavior.
- Configuration validation and redacted password output.
- Flyway migration idempotency, concurrent upserts without duplicate rows,
  timezone-aware timestamps, and database constraints.
- Persistence across a PostgreSQL process restart and unreachable-database errors.
- Atomic XP/coin writes and audit rows, cap/overdraft enforcement, concurrent
  requests, payload conflicts, injected rollback failures and timeout-safe retries.
- Commands, permissions, console behavior, optional limits, safe asynchronous
  replies and token/revision-safe cache updates after real PostgreSQL commits.
- Display configuration, animation/fallbacks, sidebar cleanup and ownership,
  persistent toggles, reload validation and many-viewer component coverage.
- Unified settings cache/API, one pending save, inventory event guards, stale
  menus, cancellation/timeouts, SQL rollback and V3 reuse after recreation/restart.

Earlier smoke tests used **Paper 26.3 build 244, Java 25, and PostgreSQL 18.6**:
plugin startup, migrations with a restricted role, console `/profile`, shutdown,
missing-password failure, and database-down startup were checked.

Actual Minecraft-client join, new command, reconnect, permission, and outage
acceptance checks are still outstanding. Neon-backed plugin operation and social
branch CI are not yet verified. See the [manual test checklist](docs/testing.md).

## Remaining work and known limitations

1. Refresh VS Code's Java project model if dependency imports remain unresolved:
   **Java: Reload Projects**, then **Java: Clean Java Language Server Workspace**
   if necessary. Earlier diagnostics came from a failed/stale Gradle import.
2. Wire Neon environment variables into Java, separating migration and runtime connections.
3. Complete in-game acceptance tests on a separate server and development database.
4. Publish and verify CI for the new social development branch, and prepare a staging release
   with backups and a rollback plan.

Current limitations:

- Single-server profile ownership only; no cross-server synchronization.
- No periodic retry after a session becomes unavailable; profiles reload on rejoin
  or a later committed mutation.
- Last-seen timestamps are best-effort, not exact presence records during crashes.
- Shutdown requests up to 10 seconds per service for worker drain; outage-time write completion needs
  further acceptance testing.
- No activity reward hooks, player transfers, shops, standalone profile GUI,
  parties, minigames, or web authentication.

Next steps are live staging acceptance, backup/restore and remote CI verification.
Later milestones can add party/game systems after social release acceptance. See the
[foundation specification](ClubCore_v0.1_Foundation_Specification.md) for the full roadmap
and definition of done.

## Further documentation

- [Architecture](docs/architecture.md): layers, threading, session lifecycle.
- [Database](docs/database.md): provisioning, schema migrations, backup/restore.
- [Configuration](docs/configuration.md): settings, secrets, troubleshooting.
- [Testing](docs/testing.md): automated coverage and manual acceptance.
- [Progression and economy](docs/progression-and-economy.md): V2 schema status and deployment gates.
- [Coin operations](docs/operations-economy.md): cap, retry policy and audit investigation.
- [Lobby displays](docs/displays.md): commands, V3 preferences, configuration and sidebar ownership.
- [Player settings](docs/settings.md): API, menu, failure states and session guards.
- [Friends development slice](docs/social.md): commands, V4, rate limits and remaining milestone work.
- [Persistence decision](docs/decisions/0001-profile-persistence.md): design rationale.

Package root: `com.inpuzah.club.core`. Suggested commit prefixes:
`feat:`, `fix:`, `test:`, `docs:`, `chore:`.
Never commit credentials, `.env` files, local server data, or generated jars.