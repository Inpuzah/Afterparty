# Club Server

Backend plugins for the Club Minecraft network.

**Current milestone:** ClubCore v0.1.0 - Player Identity & Persistence  
**Target:** Paper 26.3, Java 25, Gradle Kotlin DSL, PostgreSQL  
**Status updated:** November 7, 2026

ClubCore is the foundation for future profiles, currencies, progression, settings,
and persistent network data. Only the `core` plugin exists today; the full network
and its future systems are not implemented.

## What we have so far

| Area | Implemented |
|---|---|
| Plugin bootstrap | Main class, `plugin.yml`, bundled configuration, `/profile` registration. |
| Player identity | Immutable UUID-keyed profile with username, first join, last seen, level, XP, and coins. |
| Persistence | PostgreSQL JDBC repository, HikariCP connection pool, Flyway V1 migration. |
| Join/quit lifecycle | Async profile creation/loading on join and last-seen updates on quit. |
| Session state | `LOADING`, `READY`, `UNAVAILABLE`, and `CLOSED`, with tokens to reject stale callbacks. |
| Profile service | Async API, duplicate-load coalescing, bounded worker queue, load timeouts. |
| Failure handling | Friendly player messages; invalid configuration or database/migration failure disables the plugin at startup. |
| Packaging | Self-contained Shadow jar with relocated PostgreSQL, HikariCP, and Flyway dependencies. |
| Tests | 40 automated tests, including 11 repository tests against real embedded PostgreSQL. |
| Operations | Local Docker Compose database, provisioning script, configuration and backup documentation. |
| CI | GitHub Actions workflow for Java 25 build/tests and jar upload; remote execution not yet verified. |
| Neon | CLI/project setup and environment pull completed; Java integration remains unfinished. |

Level starts at **1**, experience at **0**, and coins at **0**. These are read-only
placeholders, not implemented economy or progression systems. Username changes
do not reset UUID identity, first-join date, or progression fields.

## Commands

| Command | Permission | Behavior |
|---|---|---|
| `/profile` | `club.profile.view` (default: everyone) | Shows your own name, level, XP, coins, and first-join date in chat. |

- Players only; console receives a helpful message.
- Extra arguments show `Usage: /profile`; lookup of other players is not supported.
- During loading: "Your profile is still loading. Try again shortly."
- During failure: "Profiles are temporarily unavailable."
- The command reads cached session state and never queries the database directly.

## Project layout

```text
core/
  src/main/java/com/inpuzah/club/core/
    ClubCorePlugin.java
    command/       Chat-based /profile command
    config/        Startup validation and secret lookup
    listener/      Player join/quit events
    model/         Immutable profile snapshot
    persistence/   Connection pool and migrations
    repository/    Blocking JDBC operations, no Bukkit types
    service/       Async profile API
    session/       Online session states and tokens
  src/main/resources/
    plugin.yml
    config.yml
    db/migration/V1__create_player_profiles.sql
  src/test/java/   Unit and PostgreSQL integration tests
docs/             Architecture, database, configuration, testing, decisions
docker/           Local database initialization
gradle/           Wrapper and pinned dependency versions
.github/workflows/build.yml
neon.ts           Neon infrastructure configuration, not a plugin runtime
```

Only `core` is included in the Gradle build. New modules should be added when they
have real implementations.

## Requirements and build

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

Deployable output: **`core/build/libs/ClubCore-0.1.0.jar`**.
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

6. Look for `Schema migration complete` and `Profile service ready` in the log.
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

The last recorded clean Gradle build passed with **40 tests, zero failures/errors,
and zero skipped tests**. This README update did not rerun the build.

Automated coverage includes:

- Profile defaults, validation, and UUID identity across username changes.
- Session transitions, quit/rejoin token checks, and stale-result rejection.
- Async load coalescing, caller timeouts, queue rejection, and shutdown behavior.
- Configuration validation and redacted password output.
- Flyway migration idempotency, concurrent upserts without duplicate rows,
  timezone-aware timestamps, and database constraints.
- Persistence across a PostgreSQL process restart and unreachable-database errors.

Earlier smoke tests used **Paper 26.3 build 244, Java 25, and PostgreSQL 18.6**:
plugin startup, migrations with a restricted role, console `/profile`, shutdown,
missing-password failure, and database-down startup were checked.

Actual Minecraft-client join, `/profile`, reconnect, permission, and outage
acceptance checks are still outstanding. Neon-backed plugin operation and CI on
GitHub are also not yet verified. See the [manual test checklist](docs/testing.md).

## Remaining work and known limitations

1. Refresh VS Code's Java project model if dependency imports remain unresolved:
   **Java: Reload Projects**, then **Java: Clean Java Language Server Workspace**
   if necessary. Earlier diagnostics came from a failed/stale Gradle import.
2. Wire Neon environment variables into Java, separating migration and runtime connections.
3. Complete in-game acceptance tests on a separate server and development database.
4. Verify CI remotely, commit/push the foundation, and prepare a staging release
   with backups and a rollback plan.

Current limitations:

- Single-server profile ownership only; no cross-server synchronization.
- No automatic retry after a session becomes unavailable; profiles reload on rejoin.
- Last-seen timestamps are best-effort, not exact presence records during crashes.
- Shutdown requests a 10-second worker drain; outage-time write completion needs
  further acceptance testing.
- No economy transactions, XP awards, settings service, profile GUI, social
  features, minigames, or web authentication.

Future milestones cover an authoritative economy service, progression service,
settings, and separate social/game systems. See the
[foundation specification](ClubCore_v0.1_Foundation_Specification.md) for the full roadmap
and definition of done.

## Further documentation

- [Architecture](docs/architecture.md): layers, threading, session lifecycle.
- [Database](docs/database.md): provisioning, schema migrations, backup/restore.
- [Configuration](docs/configuration.md): settings, secrets, troubleshooting.
- [Testing](docs/testing.md): automated coverage and manual acceptance.
- [Persistence decision](docs/decisions/0001-profile-persistence.md): design rationale.

Package root: `com.inpuzah.club.core`. Suggested commit prefixes:
`feat:`, `fix:`, `test:`, `docs:`, `chore:`.
Never commit credentials, `.env` files, local server data, or generated jars.