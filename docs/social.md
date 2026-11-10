# Friends, privacy and interactions (v0.4 development slice)

Status: **implemented locally for development, not staging-approved**.
The branch starts from merged main `3eb9cb2`. Its Java 25 baseline clean build
passed before social changes. The guide's two-client v0.3 staging checks and
backup/restore rehearsal remain outstanding; no live database was migrated.

This slice implements the guide's initial playable flow:
`/friend add <online-player>` -> `/friend accept <player>` -> `/friends`.
It also wires deny/cancel/remove and blocking to the same transactional service.
It also includes the `/settings` privacy submenu and sneak-right-click player
interaction menu. Local implementation is complete; v0.4 release acceptance
remains outstanding.

## Commands

All commands are player-only and require `club.social.use` (default everyone)
and a ready profile. Names must match an online player's exact name; no arbitrary
offline-name lookups or profile creation are performed.

| Command | Behavior |
|---|---|
| `/friend add <online-player>` | Persist a request to a ready online profile. |
| `/friend accept <online-player\|uuid>` | Accept an incoming, unexpired request. |
| `/friend deny <online-player\|uuid>` | Decline an incoming request. |
| `/friend cancel <online-player\|uuid>` | Cancel your outgoing request. |
| `/friend remove <online-player\|uuid>` | Remove an existing friendship. |
| `/friend requests [page]` | List incoming active requests and sender UUIDs. |
| `/friends [page]` | List friends and online/offline status on this Paper server. |
| `/block <online-player>` | Block the target, remove friendship and resolve requests atomically. |
| `/unblock <online-player\|uuid>` | Remove your directional block. |
| `/blocked [page]` | List players you blocked and their UUIDs. |

List pages contain at most 10 entries, ordered by UUID. Use the full canonical
UUID printed in the list to process offline relationships. An unknown UUID
cannot create a profile. Friend names come from persisted profiles, not request
payloads, and reflect the most recently stored username.

A request or acceptance notification is sent only after a new successful commit,
not on duplicate/rejected attempts. Replies and notifications use the existing
main-thread scheduler and login-token checks. Private block/privacy rejections
use a generic cannot-send/accept message; the other player's block is not exposed.

## Player interaction menu

Sneak and right-click another ready online player with your main hand while
`social.interactions-enabled` is true. Opening requires `club.social.use`, ignores
cancelled/protected interactions and does not replace an existing custom GUI.
Opening is deferred and rechecks both player logins and the original inventory.
The title is `Afterparty \u2022 Player` (the escape renders as a bullet in-game).
Ownership uses the inventory holder, not the title.

| Slot | Content/action |
|---|---|
| 4 | Target head, cached name, level, UUID and local online status. |
| 11 | Send request, accept incoming, cancel outgoing or remove friend; disabled while you block the target. |
| 13 | Read-only summary: level, lifetime XP, Club Coins and relationship. |
| 15 | Block or unblock, based only on your own directional block. |
| 22 | Close, including while a save is pending. |
| 24 | Party Invite: the exact target login, when the opt-in party runtime is ready. |

Loading, saving and unavailable states never pretend to be a confirmed
relationship. Click an unavailable action to retry the read. Actions are deferred,
rapid clicks reserve one action, and all inventory movement/drag routes are
cancelled. Every mutation revalidates relationship/privacy/block state in the
repository and is followed by an authoritative read. An outdated menu never
turns one action into a different toggle.

Timeouts warn without cancelling accepted writes. Reopening, even for another
target or after rejoin, waits for the viewer's prior menu write before reading.
Close/quit/shutdown invalidate stale callbacks; either participant quitting
closes the menu. Viewer and target login tokens prevent a new session from
receiving an old menu action. Successful new requests/acceptances notify the
other player's original online session, never duplicates or private rejections.
No graph cache, database work on the Paper thread or repeating menu timer is added.

Party Invite is independent of friendship actions and uses the shared party
invitation adapter. It checks party permission, readiness and reconnect admission;
the V5 repository rechecks blocks, invite privacy, capacity, leader and rate limits.
It preserves pending writes across reopen/rejoin and only notifies the original
target session after a new invitation commits. Disabled parties produce explicit
unavailability instead of changing friend/block state.

## Persistence and consistency

V4 adds `friendships`, `friend_requests`, `player_blocks` and
`social_preferences`. V1-V3 and existing display choices remain unchanged.
All UUIDs reference existing profiles.

Friendships use one canonical UUID pair, determined by PostgreSQL
`LEAST`/`GREATEST`, not Java signed UUID comparison or usernames. Every pair
mutation first locks the two profile rows in ascending PostgreSQL UUID order.
This serializes request/accept/remove/block operations without a global graph
lock. Privacy updates lock the same owner profile, so request creation and
acceptance revalidate the recipient's current policy and both block directions.
Blocking removes friendship and resolves pending requests in both directions
within that transaction.

Requests expire after exactly seven days. Reads, inbox limits and acceptance
exclude expired/resolved rows; no prune scheduler is required for correctness.
The schema includes `resolved_at` in addition to the guide's example columns.
Keeping one resolved row per ordered pair preserves cooldown and rolling
distinct-target counts across acceptance, denial, cancellation and restart.
Otherwise deleting requests would let an accept/cancel loop bypass rate limits.
There is no private conversation content, graph cache or automatic retry.

The recipient defaults to accepting requests (`EVERYONE`); the service can
persist `NOBODY` as `friend_requests_enabled=false`. This belongs in V4
`social_preferences`, never V3 display preferences. Open `/settings` and click
Social Privacy to change this choice. The submenu uses this same service,
confirmed values and pending/error states, with no second database write path.
See [privacy menu behavior](settings.md#social-privacy-submenu).

The v0.5 snapshot extends that same bounded service with independent
`partyInvitesEnabled` in `SocialPreferences` and `setPartyPreferences(uuid, enabled)`.
Both privacy choices are loaded/returned together under the owner's profile lock,
but each setter writes only its own flag. Party privacy uses V5 `party_preferences`
through the existing party preference repository; V4 and V3 are unchanged.
This personal control is available before party runtime registration.
See [party implementation status](parties.md#configuration-and-personal-invite-privacy).

`plugin.socialService()` exposes the asynchronous API. Mutations return a typed
`SocialResult` only after commit, or an exceptional future on storage/service
failure. Lists are bounded and immutable. Storage work uses the shared Hikari
pool with its own bounded workers (`database.pool-size`, queue 1000), not one
thread per command. Shutdown drains social workers before closing Hikari.

Caller cancellation/timeouts do not cancel accepted persistence. After an
uncertain outcome, inspect lists before retrying; do not blindly repeat an
operation. Duplicate requests/acceptances return explicit outcomes, but a future
intentional new request after a cancellation/cooldown is a new action. There is
no operation-ID ledger promising arbitrary delayed retry idempotency.

## Limits

| Config key | Default | Bounds |
|---|---|---|
| `social.interactions-enabled` | true | boolean; controls menu opening only |
| `social.requests-per-minute` | 5 distinct targets | 1-100 |
| `social.incoming-limit` | 25 active incoming requests | 1-1000 |
| `social.retry-cooldown-seconds` | 30 after request resolution | 1-86400 |

The numeric limits are enforced inside the locked database transaction, including concurrent
senders/recipients and service restarts. A retry to the same target after cooldown
does not consume another distinct-target slot. Existing configs inherit defaults.
Changes require a plugin/server restart; display-only reload does not change them.
TTL remains fixed at seven days.

## Upgrade and verification

Development output is `ClubCore-0.4.0-SNAPSHOT.jar`, not a released v0.4.0.
Deploying it **automatically applies V4 at startup**. Back up and verify restore
on an isolated staging copy before deployment; never point tests at production.
Code rollback to v0.3 leaves the additive V4 tables intact and disables social
features. Do not edit historical migrations or delete schema history.
If migrations are run separately, social mutations also need table `DELETE`
privileges in addition to `SELECT`, `INSERT` and `UPDATE`.

Automated tests cover fresh/V3 upgrades, constraints, UUID ordering across the
signed boundary, existing display/profile preservation, duplicate/expired
requests, cooldown/rate limits with an injected clock, concurrent reciprocal
requests, accept/block races, quotas, rollback injection, database restart,
real service timeout recovery, exhausted pools, bounded shutdown and command
permission/session/reply guards.
Privacy tests additionally cover inventory manipulation, late callbacks,
reconnect ordering, permission/profile changes, timeout-safe persistence,
error recovery, settings navigation and GUI-to-PostgreSQL request enforcement.
Interaction tests cover input gating, explicit action mapping, viewer/target
sessions, pending write ordering, inventory protection, timeout/error recovery
and GUI-to-PostgreSQL accept/block/unblock/privacy flows.
Item rendering still requires live Paper validation.

Before calling this slice playable in staging, use two real accounts to:

- Send, inspect and accept a request; verify both friends lists.
- Reconnect and restart Paper/database; verify persisted friendship.
- Deny/cancel and retry; confirm limits and no duplicate notifications.
- Block/unblock, verify friendship/request removal and generic rejection.
- Exercise offline UUID actions, rename, no permission and loading profiles.
- Disconnect/rejoin during delayed operations; check no old-session replies.
- Simulate DB outage and confirm explicit failure, no false success, recovery.
- Toggle Everyone/Nobody in `/settings`; restart and confirm the choice persists
  and request creation/acceptance enforces it without changing display choices.
- Spam privacy clicks, close/reopen/rejoin during saves, revoke permissions and
  try inventory movement. Confirm no duplicate writes, stale UI or stolen items.
- Sneak-right-click with both accounts in survival/creative; verify target skin,
  title, summary and all relationship actions, including outgoing cancellation.
- Disable interaction opening, try off-hand/self/ordinary clicks, existing custom
  menus and protected interactions; confirm other plugins' behavior is preserved.
- Spam interaction actions and attempt shift/number-key/offhand/drop/double-click/
  drag movement. Disconnect either participant and close/reopen during delayed
  saves; confirm no stolen items, stale-session actions or false success.
- Verify `/profile`, `/balance`, `/settings`, `/display` and lobby displays.
- Rehearse backup/restore and clean shutdown; measure realistic TPS/load.

No live Paper/client or staging backup/restore signoff is recorded.

Local verification: Java 25 `clean build --console=plain` completed
`BUILD SUCCESSFUL in 1m 56s`. JUnit XML reports **291 tests, 0 failures, 0 errors,
0 skipped**, including 81 social/privacy/interaction tests and the settings navigation test. Jar inspection confirmed
`0.4.0-SNAPSHOT`, all five social commands, V1-V4 and 42 merged Flyway providers.
The jar includes the interaction controller/holder/renderer and its enabled config flag.
The snapshot SHA-256 from that build is:

```text
32299BB98F942D21183B850375175B1C00757367230073AEA290CB84CE7A1529
```

No v0.4 release tag or remote CI result is claimed.

## Remaining v0.4 work

- Two-client acceptance, screenshots/log evidence, remote CI and release signoff.
- Staging backup/restore rehearsal and realistic load/TPS measurement.

Parties, chat, rewards, teleportation, cross-server presence and
`FRIENDS_OF_FRIENDS` policy remain out of scope.
