# Parties and communication (v0.5 development)

Status: **opt-in parties, disconnect grace, personal privacy, overview/invitation
menus, interaction invites, member notifications and private chat implemented;
live staging and release gates remain outstanding**.
Branch `feat/parties` starts from merged main
`0fe22b989498634d746eb33e44f0ad83a1914882`, including PR #3 and its subsequent
social command/menu fixes. Baseline Java 25 `clean build --console=plain` passed
in 4m 36s: **291 tests, zero failures/errors/skips**.

Existing social commands, menus, V1-V4 and display/settings behavior are retained.
The guide's live v0.3/v0.4 client acceptance and backup/restore gates remain
**blocking production deployment**, not prerequisites falsely marked complete.

## Confirmed product rules

- Parties are ephemeral. Server restart ends parties after explicit epoch
  reconciliation, not merely by hiding old rows.
- Automatically create a party on the first successful invitation; explicit
  `/party create` will also be supported. A rejected invitation must not leave
  an unexpected empty party behind.
- Maximum 8 members by default, configurable 2-32. Invitations reserve no slots.
- Invites last 120 seconds. Accept/deny addresses an immutable invitation UUID,
  never a mutable username. New invitations and acceptance require ready online
  sessions; stored UUIDs remain authoritative.
- Add personal Everyone/Nobody party-invite privacy **in this milestone**, not as
  a deferred feature. Default Everyone, additionally restricted by blocks in
  either direction. Friend privacy and display preferences remain independent.
- Disconnected members retain membership for 5 minutes. Rejoining cancels their
  old session's removal. Grace-expiry cleanup must revalidate the session.
- When a leader leaves or is removed after grace, promote the earliest-joined
  online member, then earliest-joined remaining member. Close if none remain.
  Break equal join timestamps using PostgreSQL-compatible UUID ordering.
- Blocking prevents new invitations and acceptance, but does not partition an
  already formed party's shared chat. Members may leave or be kicked.
- Party chat is local-server only, literal text, never global broadcast.
  Delivery must exclude removed members and reject stale sender sessions.

## Implemented domain contract

`party/model` contains Bukkit-free immutable records:

- `Party`: active members, leader UUID, process epoch, revision, timestamps and
  capacity. A snapshot rejects missing/duplicate members, missing leader,
  overflowed capacity, negative revision and inconsistent timestamps.
- `PartyMember`: profile UUID, validated stored username and active join time.
  Presence is not persisted or treated as an authoritative boolean in this DTO;
  presentation resolves online sessions on the Paper thread.
- `PartyInvite`: unresolved invitation identity, party/actor/recipient UUIDs and
  TTL timestamps. Expiry is inclusive at the deadline.
- `PartyPreferences`: independent personal party-invite choice.

Member lists are defensively copied, immutable and sorted by join time then UUID.
A snapshot is a read model, **not permission to mutate**. Database transactions
must recheck capacity, invite TTL, blocks, policy, leadership and membership.

Validation for this slice: Java 25
`:core:test --tests com.inpuzah.club.core.party.PartyModelTest :core:shadowJar --console=plain`
passed in 2s: **13 party tests, zero failures/errors/skips**, and the plugin jar
was rebuilt. This focused run is separate from the 291-test baseline above.

## Implemented V5 storage foundation

V5 is additive; V1-V4 remain unchanged. It adds:

- `party_instances` and `party_owners`: epoch history and the explicit predecessor
  for each stable server owner. A composite foreign key prevents owner mismatch.
- `parties`: UUID identity, instance/epoch, leader, capacity, revision and close time.
- `party_members`: surrogate membership UUID plus a partial unique player index
  for active membership. Leaving/rejoining creates a new membership identity
  instead of overwriting history.
- `party_invites`: UUID identity, TTL, resolution and abuse-limit evidence.
- `party_preferences`: separate UUID-keyed invite policy. Its JDBC repository
  persists choices and defaults to Everyone only for an existing profile without
  a choice. Missing profiles/storage errors fail explicitly. Reads do not insert
  defaults, and writes lock the profile row like V4 policy/block mutations.
  The social service/menu now reuses this persistence path for personal controls.

Deferred constraint triggers require an active leader, 1-capacity active members
for an open party, and no active members for a closed party. They allow atomic
create/transfer/close, serialize final validation with a `FOR NO KEY UPDATE` party
lock (compatible with foreign-key key-share locks), and reject moving a historical
membership to a different party. The global partial unique player index prevents
simultaneous membership in two parties. Invitations enforce positive TTL,
non-self/existing profiles, paired resolution fields and one unresolved invitation
per party/recipient. Expired unresolved invitations must be resolved transactionally
before replacement; cleanup alone will not enforce TTL.

`PartyEpochLease` acquires a session advisory lock on a dedicated physical
`PGSimpleDataSource` connection, scoped to database/schema/stable server owner.
It rejects known transaction-pooler hostnames. Use a **direct endpoint**, not
Hikari or PgBouncer transaction pooling. The same owner identifier must survive
restarts; it is not the random epoch UUID. No multi-node party support is promised.

On acquisition it locks the owner row, closes only that owner's recorded
predecessor parties/members/invites, and publishes the new epoch in one transaction.
Lease contention fails rather than stealing ownership. Graceful close ends the
current epoch before releasing its physical session; crash recovery performs the
same scoped cleanup next startup. Lost ownership or a dead connection fails
`verify()` explicitly; never silently replace the lease. The opt-in runtime now
owns this lifecycle at plugin startup/shutdown.

## Runtime ownership and failure containment

When `party.enabled` is true, `PartyRuntime` starts on one dedicated background
lifecycle executor. Before reconciling, direct/application connections must agree
on database, schema and the recorded predecessor UUID; the application endpoint
must then see the newly acquired epoch. Disagreement fails explicitly, without
publishing a service. Keep both endpoints on the same database/schema; these
checks do not make misconfigured cross-server endpoints supported.

`plugin.partyService()` returns the bounded backend only when READY; it throws
explicitly when disabled, starting, failed or stopping. A retained service also
rejects new and queued work after invalidation. Before operations and via one
shared five-second background heartbeat, the runtime verifies the physical lease.
Lost ownership/connection makes the runtime FAILED, logs the cause, stops party
work and schedules cleanup. It does not silently start a new epoch or disable
other ClubCore services. Investigate and restart to recover.

Shutdown invalidates party entry points before closing other services/the pool.
Already running work may still commit; queued work fails explicitly. The lifecycle
executor gives workers ten seconds to drain, then marks unresolved futures as
uncertain. **That deadline is not proof of worker termination**: the lease stays
held until the executor actually terminates, then the current epoch is closed and
ownership released. Plugin disable waits at most ten seconds and logs pending
cleanup, allowing background cleanup to finish as workers exit. Cleanup failures
are explicit and require next-startup reconciliation. Caller cancellation/timeouts
on lifecycle future copies do not cancel the underlying startup/cleanup.

After backend readiness, plugin startup activates commands, overview/invitation
menus and the party session listener on the Paper thread. While disabled/starting/failed,
`/party` and `/pc` explicitly report unavailability.

## Disconnect grace and reconnect ordering

One shared 20-tick server task feeds ready/admitted online UUIDs to
`PartyDisconnects`. Departures retain membership until the configurable grace
deadline (default 300 seconds). At/after the exact boundary the sweep submits
at most 16 cleanups per pass and permits at most 16 outstanding. It stores one
presence/departure per UUID, keyed by an independently minted party login token,
and removes completed offline records. There is no timer per player.

`PartySessionListener` tracks exact Player objects. Join/quit handling runs at
LOWEST priority, before ordinary gameplay/profile listeners. Rejoining cancels
the old departure's atomic claim even if removal was already queued behind
worker/epoch/profile locks. The repository attempts that claim **inside** its
transaction after epoch/profile locks and party lookup. Losing to reconnect
returns `STALE_PARTY` without changing membership.

If removal already claimed the expired login before reconnect, cancellation
cannot pretend the transaction vanished. The new login's party commands/menu/
invite eligibility waits asynchronously for its completion. Profile readiness
and normal gameplay are not blocked. Even rapid rejoin/quit/rejoin preserves
this barrier; an old callback cannot remove the replacement presence record.
Only after that result may the new login create/join another party. Runtime
party work uses one FIFO worker (the backend already serializes on the epoch),
so older accepted writes cannot execute after a later disconnect cleanup.

Removal reuses transactional leave/succession: earliest-joined ready/admitted
online member, then earliest remaining, and close when empty. A missing profile
has no possible membership due to the foreign key and returns `NOT_IN_PARTY`.
An uncertain cleanup error is logged and disables parties/starts epoch shutdown;
it is never blindly retried against potentially different membership. Other
ClubCore services stay available. Restart after investigating the failure.
Disable closes menus/cancels the shared task and unclaimed departures before
draining party workers. Claimed work still finishes before lease release.

## Transactional repository and async service

`JdbcPartyRepository` implements create, auto-create/invite, accept/deny by invite
UUID, leave, kick, promote, disband, current party lookup and paginated incoming
invitations. Outcomes have typed codes and optional party/invitation UUIDs.
No database failures are translated into empty lists or speculative success.

Each operation verifies the physical epoch lease, then locks the current active
epoch row, relevant profile rows in PostgreSQL UUID order, and party state.
The epoch row deliberately serializes party operations within one server.
This conservative first implementation avoids discovery/lock inversion, fences
epoch closure and makes snapshots coherent; it is **not** a claim of scalable
parallel party throughput. Load testing remains required. V4 block writers take
only profile locks in the same UUID order, and privacy writers lock their owner
profile. Party workers never acquire an epoch after a profile lock.

Auto-create and invitation commit together. Invalid target, blocked/privacy,
membership, leadership and rate checks happen before auto-creation. Invitations
reserve no member slots. Acceptance rechecks the deadline, current leader,
recipient policy, both block directions, single membership and current capacity.
Accepting cancels other invitations to that player in the epoch and increments
the revisions of all affected parties. Transfer/leader departure cancels the old
leader's pending invitations. Party close resolves invites and memberships.

Leave chooses the earliest-joined member in the caller's immutable online UUID
snapshot, then the earliest remaining member, with the established UUID tie break.
The lifecycle adapter captures presence and revalidates disconnect sessions
without looking up Bukkit players on workers. The backend itself has no timers.
Blocks do not evict existing party members.

`PartyPolicy` validates defaults and bounds independently of YAML: 8 members
(2-32), 120-second TTL (1-3600s), 5 distinct invite targets/minute (1-100),
10 pending invitations/party (1-100) and 30-second same-target cooldown (1-86400s).
Cooldown counts from the later creation/resolution time. Numeric limits use
persisted invitation evidence and an injectable clock, not per-process maps.
Resolved invitations still count toward the rolling distinct-target limit.
Exact expiry boundaries are rejected; reads exclude expired/resolved invites.
New invitation attempts resolve expired rows before considering replacements.
No scheduled pruning task is required for correctness.

`DefaultPartyService` reuses `BoundedServiceExecutor`: off-thread calls, bounded
workers/queue, copied futures and bounded shutdown. Caller timeout/cancellation
does not cancel accepted persistence. Online UUID input is copied before queuing.
Storage/lease failures remain exceptional and are logged. After uncertain writes,
read authoritative state before retrying; no operation-ID ledger or arbitrary
delayed-retry idempotency is promised.

These backend APIs are wired behind the opt-in runtime. The command adapter enforces
ready online actor/target sessions at invitation entry and uses existing
`CommandFeedback` login guards for committed replies/notifications. Command
registration and disconnect grace are now wired; private chat remains pending.

## Configuration and personal invite privacy

`PartyConfig` validates startup YAML with strict types and policy bounds; defaults
remain eight members, 120-second invitations and 300-second disconnect grace.
`party.enabled` defaults false. Enabling it starts the lease/service asynchronously,
then registers controls when ready. All configuration requires restart.
See the [configuration reference](configuration.md#party-groundwork-v05-snapshot).

Stable `party.owner-id` and optional explicit direct host/port prepare a dedicated
physical epoch datasource, inheriting database credentials/SSL without logging
secrets. Enabled known transaction-pooler hosts are rejected rather than guessed
or silently rewritten. Existing disabled pooled configurations stay valid.
The datasource factory is used only when the runtime is explicitly enabled.

Social Privacy now includes **Party invites: Everyone / Nobody** at slot 15,
alongside the unchanged friend-request control at slot 13. It remains available
when parties are disabled and labels availability as server-configured.
The existing bounded `SocialService` returns a combined immutable privacy snapshot;
`setPreferences` changes only friend privacy and `setPartyPreferences` changes
only party privacy. Both lock the owner's profile row and share the existing party
preference SQL implementation. No display choice or existing membership changes.

Both menu controls share one pending-write reservation and loading/saving/error
state. UI reads wait for accepted writes across close/rejoin; failures reload
authoritative choices. Caller timeouts never cancel persistence or enable a second
write. Exact holder/player/login and permissions/readiness checks suppress stale
callbacks. Loading clicks cannot become unseen actions after an async read.
An actual menu -> bounded social service -> PostgreSQL -> party repository test
verifies new invite and pending acceptance rejection while friend/display choices
remain independent. Live Paper item rendering/client acceptance remains open.

## Party command adapter

`PartyCommand` implements the following command paths behind `club.party.use`:

| Path | Behavior |
|---|---|
| `/party` | Opens the overview menu; when menu-disabled, directs to `/party help`. |
| `/party create` | Explicitly create a party. |
| `/party invite <online-player>` | Exact ready online target; auto-create follows the repository policy. |
| `/party invites [page]` | Bounded incoming invitations, 10 per page with expiry and clickable actions. |
| `/party accept <invite-uuid>` | Accept one exact immutable invitation ID. |
| `/party deny <invite-uuid>` | Decline one exact immutable invitation ID. |
| `/party list` | Stored member names/UUIDs, leader marker, capacity and local online presence. |
| `/party leave` | Leave with a captured immutable snapshot of ready online UUIDs for succession. |
| `/party kick <online-player\|uuid>` | Remove an existing member; leadership is checked in the repository. |
| `/party promote <online-player\|uuid>` | Transfer leadership to an existing member. |
| `/party disband` | Leader-only closure. |
| `/party help` | Syntax and ephemeral lifetime/grace explanation. |

`plugin.yml` declares `/party` and default-true `club.party.use`. When enabled,
`ClubCorePlugin` installs the active adapter only after backend readiness and
session/grace setup. No `/pc` or `/party chat` path exists yet.

The adapter rejects console, missing permissions, disabled/unavailable subsystem
and non-ready actors before storage. New invitations never resolve arbitrary
offline names or accept a UUID instead of an online name. Existing-member actions
may use full UUIDs; accept/deny never use names or abbreviated UUIDs. Tab completion
lists only ready online candidates, does not query storage and respects access.
List pages are capped at 1,000,000 to bound offset arithmetic.

Replies and successful invitation notifications are marshalled to the Paper
thread through `CommandFeedback`, with exact player/login checks and current
permission/readiness/subsystem checks. Notifications occur only for a newly
committed invitation, not duplicates or private rejections. Accept/deny buttons
execute the exact saved UUID. Player text is literal Adventure content, not
MiniMessage markup. Recipient names are shown when currently online; UUIDs stay
authoritative for offline inviters.

Caller deadlines act on a future copy. An uncertain reply warns that persistence
may complete and directs the caller to authoritative lists before retrying.
A late successful invitation may still notify its original recipient session.
Closing/rejoining, revoking permissions or disabling the subsystem suppresses stale
callbacks without pretending an accepted database mutation was cancelled.

## Party overview menu

`PartyMenuController` provides a holder-bound 27-slot inventory. Plugin startup
constructs/registers it after backend readiness and opens it through `/party`
when `party.menu-enabled` is true. That flag alone does not enable parties.

| Slot (zero-based) | Function |
|---|---|
| 0 / 8 | Previous / next member page (seven members/page, up to 32 total) |
| 4 | Confirmed overview/status; click to refresh or retry |
| 10-16 | Stored member names/UUIDs, leader and local ready-online presence |
| 18 | Incoming invitation inventory: exact UUID accept/deny |
| 20 | Ready-online candidate picker; exact target session invite |
| 22 | Suggested `/pc <message>` usage, or explicit disabled notice |
| 24 | Create when absent; leave when a member; leaders right-click to arm disband, then separately left-click to confirm |
| 26 | Close |

Member clicks display the exact stored UUID. Leaders receive **suggested**, not
automatically executed, kick/promote commands for other members. Names are literal
Adventure text. No external skin/profile lookup or display-loop SQL is performed.

The menu retains last-confirmed members while loading/saving/unavailable.
Ordinary left/right clicks reserve one deferred action; all inventory movement,
bottom/outside clicks, shift/double clicks, number/offhand swaps, drops and drags
are cancelled without becoming mutations. Loading-state clicks cannot become
unseen actions when reads finish. Disband confirmation is cleared on refresh,
page navigation, save or reopen.

Before a mutation, a fresh asynchronous party read returns to the server thread
for player/permission/readiness/holder/login and displayed ID/revision checks.
`leaveIfCurrent` / `disbandIfCurrent` then **recheck party ID and revision inside
the mutation transaction**, not only before queuing. A concurrent change returns
`STALE_PARTY` without changing membership. The ordinary command APIs preserve
their existing behavior and still check current membership/leadership.

Writes survive menu close/rejoin; new menu reads wait behind the accepted result.
A timeout cannot cancel the write or unlock another mutation. Failed/uncertain
results explicitly reload authoritative state; no success or empty-party fallback
is invented. Close/rejoin, permissions, disabled runtime and shutdown suppress
stale actions/callbacks. There is no per-menu repeating task.

Tests cover up to 32 members/five pages, explicit confirmation, stale preflight
and transactional races/replacement-party guards, inventory protection, deferred
actions, pending read/write ordering, timeouts, permission/runtime loss and real
menu -> bounded service -> PostgreSQL create/disband. Actual Paper item rendering
and live client acceptance remain pending.

## Invitation inventories and interaction invites

The holder-bound 27-slot incoming/candidate menus show 18 entries/page. Slots
18/19/20/25/26 provide back/previous/refresh/next/close. Incoming entries preserve
the full invitation UUID and expiry: left-click accepts, right-click denies.
The repository still rechecks recipient, expiry, leadership, capacity, blocks
and current personal privacy. Empty or unavailable data is never assumed to
authorize a write.

Candidates come only from ready/admitted players on this server, excluding the
viewer and existing members. Ordering is name then UUID. The displayed UUID and
login token must still match when the deferred click executes; a replacement
login is not silently invited. Only a leader (or a player without a party) can
open a usable candidate list. Capacity and other policies remain transactional.

All movement/drag/shift/bottom click routes are cancelled. Loading clicks cannot
become unseen writes. Pending mutations survive close/reopen/rejoin, and new
reads wait for their actual completion. Deadline copies warn without cancelling
the accepted work; uncertain results reload authoritative lists. Exact player,
holder, permission, token, revision, admission and runtime guards protect callbacks.

The social interaction menu adds **Party Invite** at slot 24 without changing
friend/block actions. Commands, this action and the candidate menu use the same
`PartyInvitations` adapter: one pending invitation/player across these surfaces,
new-commit-only clickable accept/deny notices, and original recipient login guards.
The backend's persistent invitation rate/cooldown limits remain authoritative.

## Committed member notifications

The runtime's single FIFO worker captures before/after membership snapshots around
successful mutations and publishes `PartyChange` only after the operation commits.
Every command/menu/disconnect path uses this shared service, not speculative GUI
state. Join, leave, kick, leadership transfer and closure notices go to affected
members' ready original sessions on the Paper thread. Rejected actions and
unchanged membership do not invent notices. Observer failures log explicitly;
notices are best-effort ephemeral feedback, not a durable event log.

## Private local party chat

`/pc <message>` requires `club.party.use` and `club.party.chat`, both default true,
plus enabled/ready parties and a ready/admitted sender. `party.chat-enabled`
controls chat independently of `party.menu-enabled`. `club.party.muted` defaults
false; explicitly granting it denies **sending**, not receiving.

Messages are literal Adventure text, including strings resembling MiniMessage,
commands or placeholders. They are never globally broadcast or persisted. The
limit is 256 UTF-16 code units, with blank/control/format/line-separator/unpaired
surrogate rejection, at most one pending delivery/player and one message/second.
The exact one-second boundary is allowed. No message content is intentionally
logged; uncertain/partial delivery is not automatically retried.

Authorization is not a stale async read followed by an unguarded send. The worker's
`withParty` read retains the epoch/profile transaction locks while the Paper
callback validates the original sender, current recipient logins, readiness,
admission and permissions, applies moderation, then sends. A committed kick,
leave or disband cannot overtake delivery. A callback not started in two seconds
is cancelled before releasing the locks. If it has started, the worker retains
the locks until it actually finishes. SQL never runs on Paper.

The synchronous cancellable `PartyChatEvent` exposes the sender, party UUID and
immutable message before delivery. External mute/filter plugins must cancel this
event or grant the explicit denial permission; **global-chat-only mute plugins
are not automatically integrated**. Listeners must not wait on party service
futures or perform blocking work: they run on Paper while the party transaction
is held. Test the actual server moderation plugin before production signoff.
Blocking controls invitations/acceptance, not existing group-chat partitioning.

## Verification and deployment warning

PostgreSQL tests cover fresh/V4 upgrade and rerun, preservation of profiles,
display choices and V4 data, a schema-owning non-superuser migration, deferred
leadership/capacity checks, competing memberships/capacity, historical rejoin,
invitation constraints, independent privacy defaults/save/rollback, lease contention,
graceful close, killed-session recovery, actual PostgreSQL restart and injected
reconciliation rollback. Other owners' parties remain untouched.
Repository/service tests additionally cover auto-creation rollback, duplicate
actions, TTL/cooldown/rate/inbox limits, competing accepts/capacity, cross-party
membership races, V4 block/privacy races, leadership succession, disband/accept
races, failed accept/transfer rollback, bounded queues, copied presence,
timeout/cancellation isolation, deadline/interrupted shutdown and a real async
service-to-PostgreSQL flow.
Command tests cover player/permission/readiness checks, disabled state, syntax,
UUID/name resolution, bounded pages, tab completion, local presence, explicit
outcome messages, clickable invite IDs, late commits, disconnect/rejoin and
permission guards. An additional real command -> async service -> PostgreSQL
flow verifies invite/accept/promote/leave/disband and private-policy rejection.

The development artifact is now `ClubCore-0.5.0-SNAPSHOT.jar`.
**Deploying it automatically applies V5**. Player feature work is implemented, but
live acceptance and operational gates remain. Do not deploy it to production. No live database/Neon schema was
changed during these isolated tests. Back up and verify restore on staging first.

Latest Java 25 `clean build --console=plain`: **BUILD SUCCESSFUL in 4m 54s**.
JUnit XML totals: **483 tests, zero failures/errors/skips**, including **181 party
tests** (13 domain, 19 schema/epoch, 30 transactional repository, 12 async service,
16 command, 7 configuration, 12 runtime, 21 overview menu, 14 invitation menu,
10 disconnect manager, 4 session listener, 13 chat, 7 shared invitation adapter,
3 member notification). Social/menu suites additionally cover the new interaction
invite action,
combined privacy paths and backend policy enforcement. Jar inspection confirmed
V1-V5, party configuration/runtime/repository/service/command/menu/chat classes and 42 merged
Flyway providers. V1-V4 are unchanged and
`git diff --check` passes. Snapshot SHA-256:

```text
7B7504E4CB3A7205FBBA1FB37CB2EBAF305D98F7AE0982DBD2402EBA2E19BBF9
```

An earlier runtime-slice regression run hit the pre-existing
`DefaultProfileServiceTest.newLoadIsIssuedAfterPreviousCompletes` completion/
in-flight-removal race (expected two loads, observed one). It passed in isolation
and in subsequent full builds, including the clean run above. No unrelated
profile-service behavior was changed to conceal that intermittent failure.

## Remaining implementation and release gates

The command, menu and event adapters are covered with server/inventory proxies,
and storage flows use isolated real PostgreSQL. This is not a claim that plugin
activation, inventory rendering or reconnect behavior has been exercised on live
Paper clients. Run three-client staging acceptance with both enabled/disabled
parties/menu configurations, short and default grace, queue contention,
leader/last-member disconnect, repeated rejoin, lease loss and shutdown.

1. Remote CI on this exact unpublished branch.
2. Three-client Paper acceptance, including all inventories, permission/mute policy,
   incoming accept/deny, interaction invites, notifications and non-member chat exclusion.
3. Live crash/restart and lease-loss tests, backup/restore rehearsal and load/TPS evidence.
4. Integration with the actual staging moderation plugin via `PartyChatEvent` or
   explicit `club.party.muted` denial.

Party configuration, opt-in commands/menus/lifecycle, communication and personal privacy
are exposed. No v0.5 release tag, live database migration or production signoff is claimed.
