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

## VS Code diagnostics

If Gradle builds successfully but VS Code cannot resolve Flyway or JUnit imports,
run **Java: Reload Projects** from the Command Palette. If stale errors remain,
run **Java: Clean Java Language Server Workspace** and allow the window to reload.
The workspace enables automatic Gradle build-configuration updates and explicitly
associates the Bukkit schema with `plugin.yml`; do not add an unrelated `api` key
to satisfy a misdetected YAML schema.

## Manual Paper acceptance checklist

Use a separate test server. Record the exact Paper build and Java version in the release notes.

- [ ] Server starts with ClubCore enabled. The log shows `Schema migration complete` and `Profile service ready`, with no warnings or errors.
- [ ] `/plugins` lists ClubCore.
- [ ] First join creates exactly one row: `SELECT * FROM club.player_profiles;`
- [ ] `/profile` shows name, Level 1, 0 XP, 0 Coins and today's date (UTC).
- [ ] `/profile foo` shows `Usage: /profile`.
- [ ] Console `profile` prints "This command can only be used by a player."
- [ ] A player without `club.profile.view` is denied.
- [ ] Rejoin and server restart both keep `first_joined_at` and the stats unchanged.
- [ ] Two different accounts get separate rows.
- [ ] Join, disconnect immediately and rejoin: no stale messages, and `/profile` works.
- [ ] Stop PostgreSQL while players are online, then rejoin. "Profiles are temporarily unavailable." appears, `/profile` says the same, and TPS stays at 20 (check with `/tps` or `/spark tps`).
- [ ] Start the server with PostgreSQL down: ClubCore logs a clear error within about `connection-timeout-ms` and disables; the server keeps running.
- [ ] Start PostgreSQL again and restart the server: everything works normally.
- [ ] Logs contain no passwords and no unexpected stack traces.
