# Club Coins operations

## Current scope

The v0.2 plugin starts `EconomyService`, drains it before closing the database and
exposes `/balance` and `/clubadmin coins`. It refreshes online profile snapshots
after committed mutations. Player transfers, activity rewards and purchases are
not implemented. Staging acceptance and deployment gates are still outstanding;
do not treat this build as a production-ready economy release.

## Admin commands

```text
/balance
/clubadmin coins add <online-player|uuid> <amount> [reason]
/clubadmin coins remove <online-player|uuid> <amount> [reason]
/clubadmin xp add <online-player|uuid> <amount> [reason]
```

`/balance` is player-only (`club.balance.view`, default everyone). Admin commands
work from console or a player with `club.admin.coins` / `club.admin.xp` (default
op). Names must resolve to an online player; offline targets require a full UUID
with an existing profile. No command creates arbitrary offline profiles.

Each invocation creates and logs a new operation ID. Audit metadata uses source
`admin-command`, the exact reason (default `Administrator command`) and actor UUID
(null for console). Optional per-command limits default to **0: unlimited**, as
requested; domain limits always apply. See [configuration.md](configuration.md).

On timeout/error, do not blindly repeat the admin command: that generates a new
ID and could charge or award twice. Inspect the logged ID and audit record first.
The trusted service supports exact-ID retries, but there is no admin retry command.

## Rules

- Starting balance: 0. Currency: Club Coins, whole numbers only.
- Maximum credited balance: 1,000,000,000 coins.
- Debit amounts must be positive and cannot exceed available funds.
- Legacy balances above the cap are readable and reducible; new credits fail.
- Only committed successful operations create audit rows. Failed insufficient
  funds checks are not persisted and do not reserve an operation ID.
- Keep balances authoritative in `player_profiles.coins`; cached profiles are
  display projections, not permission to spend.

## Safe callers and retries

1. Authorize the activity/admin action before submitting work.
2. Generate one operation UUID per distinct action. Retain the UUID and the
   exact player, amount, credit/debit type, source, reason and optional actor.
3. Call the async service and inspect the completed result. Announce success
   only after `SUCCESS`; `INSUFFICIENT_FUNDS` is a rejection, not an outage.
4. On timeout, transport failure, or shutdown with an unknown outcome, retry the
   **same ID and payload**. An accepted transaction may have committed even
   though the caller did not see its response. A new ID can double-grant/charge.
5. A different payload under a committed ID is rejected as `OPERATION_CONFLICT`.
   A successful replay reports the original balances, even after later activity.
6. Failed insufficient-funds requests have no durable result. Resubmitting after
   funding can succeed. Use a fresh ID for a genuinely new purchase attempt.

Command adapters move Bukkit replies to the primary thread. The plugin-managed
services invalidate and asynchronously refresh the current online snapshot,
with login-token and read-revision guards. `/profile` can show loading briefly,
or unavailable if the refresh fails, even though a mutation committed. This does
not turn a confirmed database success into a failed coin operation. Replayed
successes refresh current totals; they do not write again or publish reward events.

## Investigation and deployment

- Persist operation IDs in the calling workflow and include them in server-side
  diagnostic logs. Never expose raw SQL errors or credentials to players.
- Use read-only queries to inspect `currency_transactions` by `operation_id` or
  `player_uuid`. Rows include signed delta, balance after, type, source, reason,
  optional actor UUID and a database timestamp.
- The sum of ledger deltas should match changes since ledger adoption, **not
  necessarily the entire balance**: V2 deliberately leaves pre-existing balances
  unchanged and creates no historical opening-balance entries.
- Never repair balances with ad hoc updates that bypass the ledger. Define and
  test a controlled correction through the service before making live changes.
- Back up and verify restore on isolated staging before deploying V2. Application
  rollback does not undo schema changes. See [database.md](database.md).
- Migration/transaction tests use disposable PostgreSQL only. No live database
  was modified to verify this implementation.
