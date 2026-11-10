# Database

## Local development (Docker)

```sh
cp .env.example .env    # then edit both passwords
docker compose up -d
```

This starts PostgreSQL 18 on `127.0.0.1:5432` (localhost only) with:

- database `club_dev`, owned by the admin role `club_admin`
- application role `club_app`, created by [docker/postgres-init/01-club-app.sh](../docker/postgres-init/01-club-app.sh)

The init script runs only when the volume is first created. To start over, run `docker compose down -v`.

## Manual provisioning (non-Docker, staging, production)

Run this as an administrator, replacing `club_dev` and the password for each environment (`club_dev`, `club_stage`, `club_prod`; each gets its own credentials):

```sql
CREATE DATABASE club_dev;
CREATE ROLE club_app LOGIN PASSWORD '<strong password>';

\c club_dev
REVOKE ALL ON DATABASE club_dev FROM PUBLIC;
GRANT CONNECT ON DATABASE club_dev TO club_app;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
CREATE SCHEMA club AUTHORIZATION club_app;
ALTER ROLE club_app IN DATABASE club_dev SET search_path = club;
```

What `club_app` can and cannot do:

- It can connect to its own database and owns only the `club` schema. It needs that ownership because the plugin runs Flyway migrations at startup.
- It cannot create databases or roles, and it cannot touch other schemas.
- If you later run migrations from a separate admin pipeline, transfer schema ownership to a migration role and grant `club_app` only `SELECT, INSERT, UPDATE` on its tables.

Network rules:

- Keep PostgreSQL bound to localhost or a private network. Never expose port 5432 publicly.
- Never give database credentials to players or the website.

## Migrations

- Flyway applies `core/src/main/resources/db/migration/V<n>__<description>.sql` automatically when the plugin enables. If migration fails, the plugin disables itself and no profile operations run.
- Applied migrations are recorded in `club.flyway_schema_history`.
- **Never edit a migration that has been applied anywhere.** Add a new `V<n+1>__...sql` instead; Flyway validates checksums and will refuse to start if an applied migration has changed.
- Test new migrations against a fresh database: `./gradlew :core:test` runs them on embedded PostgreSQL.

## Schema (V1)

V1 is unchanged. V2 adds `currency_transactions` and `experience_awards` audit
tables without altering existing profile rows. See
[progression and economy](progression-and-economy.md) for transactional services,
commands, test coverage and deployment gates. V3 adds `display_preferences`
(`player_uuid` primary/foreign key, `show_bossbar`, `show_scoreboard`, `updated_at`).
Flags default to true; changes are asynchronous and independent. This display
slice does not modify V1/V2, XP, coins or audit rows. Back up before deploying the
jar: all three migrations run automatically. See [displays.md](displays.md).

The v0.4 development jar adds **V4 social tables** (`friendships`,
`friend_requests`, `player_blocks`, `social_preferences`). Requests include a
resolution timestamp so cooldown/rate checks survive cancellation and restart.
V4 is additive and preserves V1-V3 data; it still applies automatically at
startup and needs a staging backup/restore rehearsal before deployment.
See [social persistence](social.md#persistence-and-consistency).
Social mutations use `DELETE` as well as `SELECT`, `INSERT` and `UPDATE`;
account for that when using a separate migration role.

`player_profiles`:

| Column | Description |
|---|---|
| `uuid` | Primary key and canonical identity. |
| `username` | Last seen name; never used as a key. |
| `first_joined_at`, `last_seen_at`, `created_at`, `updated_at` | `TIMESTAMPTZ`, set by the database (`NOW()`), displayed in UTC. |
| `level`, `experience`, `coins` | Materialized progression/currency totals with `CHECK` constraints. Dedicated transactional services update these alongside the V2 audit ledgers. |

A join runs `INSERT ... ON CONFLICT (uuid) DO UPDATE`, which updates only `username`, `last_seen_at` and `updated_at`. It never resets progress or `first_joined_at`.

## Backup and restore

Back up regularly and before every deployment:

```sh
pg_dump --format=custom --file=club_prod_$(date +%F).dump --dbname=club_prod --username=club_admin
```

Test restores into an **isolated** database, never over production:

```sh
createdb --username=club_admin club_restore_test
pg_restore --dbname=club_restore_test --no-owner --role=club_app club_prod_YYYY-MM-DD.dump
```

Then check row counts (`SELECT COUNT(*) FROM club.player_profiles;`) and drop the test database.
