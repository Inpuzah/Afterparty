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
| `database.pool-size` | `4` | 1-20 | Also the number of profile worker threads. |
| `database.connection-timeout-ms` | `5000` | 250-60000 | Also bounds how long startup waits when the database is down. |
| `profile.load-timeout-ms` | `10000` | 1000-60000 | After this long, the session becomes `UNAVAILABLE`. |
| `profile.show-ready-message` | `false` | `true` / `false` | Sends "Your profile is ready." on load. |

## Log reference

All ClubCore log lines are prefixed `[ClubCore]`. Bundled libraries log under `com.inpuzah.club.core.libs.*`.

| Message | Meaning |
|---|---|
| `Database: DatabaseConfig[url=..., username=...]` | Effective settings (no password). |
| `Schema migration complete: N migration(s) applied, schema version V` | Flyway succeeded. |
| `Profile service ready` | Startup finished; joins will load profiles. |
| `Invalid configuration: ...; disabling ClubCore.` | Fix `config.yml` or set `CLUB_DB_PASSWORD`. |
| `Could not connect to or migrate the database; disabling ClubCore.` | Check host, port, credentials, `pg_hba.conf`, migration errors (stack trace follows). |
| `Profile load for X (uuid) timed out after N ms` | Database slow or overloaded. |
| `Profile load for X (uuid) failed: database unavailable` | Connection lost during play; stack trace follows. |
| `N profile write(s) did not finish within 10s and were abandoned` | Shutdown with an unhealthy database; those `last_seen_at` values are stale. |

## Troubleshooting

- **Plugin disabled at startup:** read the first `[ClubCore] ERROR` line. It names the bad key or the connection failure.
- **`password authentication failed`:** the effective password does not match the database role. A non-empty `CLUB_DB_PASSWORD` overrides `database.password`; check the server process's environment as well as its runtime config.
- **`permission denied for schema`:** the role doesn't own its schema or has the wrong `search_path`; re-run the provisioning SQL in [database.md](database.md).
- **Players see "Profiles are temporarily unavailable."** The database became unreachable after startup. Restore it; players get a fresh load on their next join.
