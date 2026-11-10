# Configuration

On first start, ClubCore writes `plugins/ClubCore/config.yml`. Every value is validated at startup. If anything is missing, has the wrong type, or is out of range, ClubCore logs the problem and disables itself.

## Secrets

The password is read from a non-empty `CLUB_DB_PASSWORD` environment variable first.
If it is unset or empty, ClubCore uses `database.password` in the server's runtime
`plugins/ClubCore/config.yml`. If neither source has a password, startup fails.
Existing configuration files are not overwritten when the plugin is updated;
add the password key manually:

```yaml
database:
  password: "your actual database-role password"
```

Keep real passwords out of the repository's sample configuration and restrict
access to the runtime file. Alternatively, set the variable in the server's start script or service unit:

```sh
CLUB_DB_PASSWORD='...' java -Xmx4G -jar paper.jar --nogui
```

```powershell
$env:CLUB_DB_PASSWORD = '...'; java -Xmx4G -jar paper.jar --nogui
```

ClubCore never logs the password, and its connection URL contains no credentials.

## Reference

| Key | Default | Allowed | Notes |
|---|---|---|---|
| `database.host` | `127.0.0.1` | hostname, IPv4, `[IPv6]` | |
| `database.port` | `5432` | 1-65535 | |
| `database.name` | `club_dev` | identifier | One database per environment. |
| `database.username` | `club_app` | identifier | Least-privilege role; see [database.md](database.md). |
| `database.password` | empty | string | Runtime-config fallback when `CLUB_DB_PASSWORD` is unset or empty. Not trimmed. |
| `database.ssl-mode` | `prefer` | `disable`, `allow`, `prefer`, `require`, `verify-ca`, `verify-full` | Use `verify-full` when the database is on another host. |
| `database.pool-size` | `4` | 1-20 | Shared connection limit; profile, XP, coin and social services each have this many workers. |
| `database.connection-timeout-ms` | `5000` | 250-60000 | Also bounds how long startup waits when the database is down. |
| `profile.load-timeout-ms` | `10000` | 1000-60000 | Bounds join/refresh/settings reads and command/menu response waits; does not cancel accepted JDBC mutations. |
| `profile.show-ready-message` | `false` | `true` / `false` | Sends "Your profile is ready." on load. |
| `admin-commands.max-xp` | `0` | 0..`Long.MAX_VALUE` | Max amount per XP grant; 0 means no command limit. |
| `admin-commands.max-coins` | `0` | 0..`Long.MAX_VALUE` | Max amount per coin add/remove; 0 means no command limit. |

Display settings are under `displays`, with defaults inherited by existing
runtime configurations. See [displays.md](displays.md) for all settings, markup
validation and the display-only `/clubadmin displays reload` command.

The v0.4 development slice adds `social.interactions-enabled` (boolean, default
`true`) to enable sneak-right-click player menus, `social.requests-per-minute` (default 5, bounds
1-100), `social.incoming-limit` (25, bounds 1-1000) and
`social.retry-cooldown-seconds` (30, bounds 1-86400). Existing configs inherit
these defaults. They apply transactionally in PostgreSQL and require a restart
to change; display-only reload does not reload social settings. Requests expire
after a fixed seven days. See [social limits](social.md#limits).

The [player settings menu](settings.md) adds no configuration keys. Its visibility
choices live in PostgreSQL V3, not YAML; server-wide switches do not erase them.

Existing configurations without `admin-commands` remain valid and default to no
per-command limit, as requested. To enable safety limits, add the section and
restart; there is no config reload command yet. Positive whole amounts, lifetime
XP overflow prevention, the 1-billion coin balance cap and overdraft prevention
always apply, even when command limits are zero.

## Log reference

All ClubCore log lines are prefixed `[ClubCore]`. Bundled libraries log under `com.inpuzah.club.core.libs.*`.

| Message | Meaning |
|---|---|
| `Database: DatabaseConfig[url=..., username=...]` | Effective settings (no password). |
| `Schema migration complete: N migration(s) applied, schema version V` | Flyway succeeded. |
| `Profile, progression and economy services ready` | Startup finished; profiles and new commands are registered. |
| `Invalid configuration: ...; disabling ClubCore.` | Fix `config.yml` or set `CLUB_DB_PASSWORD`. |
| `Could not connect to or migrate the database; disabling ClubCore.` | Check host, port, credentials, `pg_hba.conf`, migration errors (stack trace follows). |
| `Profile load for X (uuid) timed out after N ms` | Database slow or overloaded. |
| `Profile load for X (uuid) failed: database unavailable` | Connection lost during play; stack trace follows. |
| `Admin operation UUID: ...` | Audit correlation for accepted admin commands and their outcomes; actor is an administrator UUID or console. |
| `Could not refresh profile ...; cache unavailable` | Committed data may exist, but `/profile` must not show an old snapshot. |
| `Settings save outcome uncertain for ...; reloading authoritative choices` | No save success was confirmed; the service retains confirmed choices and performs an ordered recovery read. |
| `Settings unavailable for ...; use /settings or /display status to retry` | Load/recovery failed or timed out; root cause follows in logs. |
| `N SERVICE request(s) unresolved after 10s; in-flight commits may have succeeded` | Investigate operation IDs before retrying mutations; last-seen writes remain best-effort. |

## Troubleshooting

- **Plugin disabled at startup:** read the first `[ClubCore] ERROR` line. It names the bad key or the connection failure.
- **`password authentication failed`:** the effective password does not match the database role. A non-empty `CLUB_DB_PASSWORD` overrides `database.password`; check the server process's environment as well as its runtime config.
- **`permission denied for schema`:** the role doesn't own its schema or has the wrong `search_path`; re-run the provisioning SQL in [database.md](database.md).
- **Players see "Profiles are temporarily unavailable."** The database became unreachable after startup. Restore it; players get a fresh load on their next join.
