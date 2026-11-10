# Player settings (v0.5 development)

`/settings` opens a 27-slot inventory for the invoking player. Permission
`club.settings` defaults to everyone. Console, extra arguments, unavailable
profiles and unknown settings are rejected with a helpful message.

| Slot (zero-based) | Function |
|---|---|
| 4 | Header |
| 11 | Bossbar visibility toggle |
| 13 | Sidebar visibility toggle |
| 15 | Open the Social Privacy submenu |
| 22 | Close |

Ordinary left/right clicks toggle one setting. Decorative slots do nothing.
All inventory movements while this menu is open are cancelled, including bottom
inventory, shift-click, number/offhand swaps, drops, double-clicks and drags.
Inventory changes are deferred out of the click callback. Rapid queued clicks
reserve one action, and the settings service rejects a second in-flight save.

Enabled/Disabled are confirmed persisted values. Saving retains the previous
confirmed choices until commit. Loading/Unavailable disable mutation; failures
never claim success. An uncertain save logs its cause and reloads authoritative
choices. Reopen `/settings` or use `/display status` to retry an unavailable
load. A caller timeout does not cancel accepted storage work, and a late
successful save still refreshes current displays and an eligible open menu.

Closing the menu does not cancel persistence. Replies and refreshes require
the same player object, profile login token, holder and current inventory.
Closing/reopening, disconnecting/rejoining, revoked permissions and plugin
disable cannot let an old callback control a replacement menu.

## Shared API and persistence

`plugin.settingsService()` exposes `PlayerSettingsService`:

- `get(uuid)` asynchronously loads/retries for an active settings session.
- `cached(uuid)` returns the last confirmed immutable `PlayerSettings`, if known.
- `state(uuid)` distinguishes loading, ready, saving, unavailable and offline.
- `setBossbarVisible(uuid, value)` / `setScoreboardVisible(uuid, value)` persist
  asynchronously and return the committed snapshot.

Use setters only for online players with a ready profile. Inspect state before
using cached choices for presentation: a retained snapshot is not a successful
recovery. Returned futures isolate caller cancellation/timeouts from persistence.
The plugin owns service shutdown.

`/display`, the menu and displays consume this single settings cache. The existing
bounded single preference worker preserves load/save order across reconnects.
No SQL occurs during menu rendering or display animation.

V3 `display_preferences` remains unchanged, with both flags defaulting to true.
Existing flags survive upgrade; display choices need no data conversion. Server-wide
display switches and temporary game suppression do not rewrite player choices.
The effective display remains `serverEnabled && playerEnabled && ownerAllows`.
The shared five-tick display scheduler and sidebar ownership policy are unchanged.

Display settings add no config keys. `/clubadmin displays reload` changes only display
configuration, not player choices, service sessions or menu ownership.

## Social Privacy submenu

The book at slot 15 opens a separate 27-slot inventory. Access requires both
`club.settings` and `club.social.use` plus a ready profile. No inactive
`FRIENDS_OF_FRIENDS` policy is exposed.

| Slot (zero-based) | Function |
|---|---|
| 4 | Privacy information |
| 13 | Friend requests: Everyone / Nobody; click to toggle |
| 15 | Party invites: Everyone / Nobody; click to toggle |
| 18 | Back to the main settings menu |
| 22 | Close |

Both policies load as one coherent snapshot asynchronously on open. Unknown values
show Loading or Unavailable, never an assumed Everyone. A save retains both last confirmed choices
and shows Saving until the actual result arrives. A timeout warns that the write
may still complete; it does not unlock another write. Failed/uncertain saves
reload the persisted policy before further changes. Click an unavailable privacy
item to retry a failed load. One pending action spans both controls, preventing
rapid cross-toggle clicks from racing or applying an unseen loading-state action.

Closing, reopening or reconnecting cannot cancel an accepted save or make a new
read overtake it. Only an eligible current holder/player/login can receive UI
updates. Both permissions and profile readiness are rechecked before action;
inventory movement is cancelled and close/back actions are deferred. No
per-menu timer is used. Menus are invalidated before social workers drain on disable.

`SocialService.preferences` returns immutable `SocialPreferences` with
`friendRequestsEnabled` and `partyInvitesEnabled`. The bounded social worker
handles both saves: `setPreferences` writes only V4 friend privacy;
`setPartyPreferences` writes only V5 party privacy via the shared party preference
repository. Each returns both confirmed choices under the owner's profile-row
lock. V3 display choices remain separate and unchanged.

Everyone is the default when an existing profile has no saved choice; reading
does not insert rows. Nobody rejects new requests/invitations and acceptance of
an older pending request/invitation. Neither toggle removes existing friendships
or party memberships. Blocks still apply in both directions. The party toggle
works even with `party.enabled: false`, so the personal choice is retained before
party runtime registration. The menu notes that party availability depends on
server configuration. See [social persistence](social.md) and [party status](parties.md).

See the [schema decision](decisions/0002-unified-player-settings.md) and
[manual acceptance record](testing/settings-manual-acceptance.md). This is a
development implementation, not a staging-approved release.
