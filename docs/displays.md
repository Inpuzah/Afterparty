# Lobby displays (v0.2.0)

## Features and commands

ClubCore owns an animated Adventure bossbar and a per-player sidebar. Both are
enabled by default, configurable independently, and driven by **one shared
five-tick task**, not one task per player.

| Command | Permission (default) |
|---|---|
| `/display status` | `club.display` (everyone), player-only |
| `/display bossbar on\|off` | `club.display` (everyone), player-only |
| `/display scoreboard on\|off` | `club.display` (everyone), player-only |
| `/clubadmin displays reload` | `club.admin.displays` (op), console supported |

Toggles are genuinely persistent. Forward-only migration
`V3__display_preferences.sql` adds the UUID-keyed flags and timestamp with a foreign
key to `player_profiles`. Neither V1 nor V2 was rewritten. New players default to
both enabled; missing preference rows are read as defaults without writes.
Saves change only the requested flag and use a bounded, single worker to preserve
save/load ordering across rapid toggles and reconnects.

Preferences load asynchronously on join. Displays stay hidden until preferences
are known, avoiding a flash of UI previously disabled by the player. Once loaded,
profile-dependent fields show `Loading...` / `Unavailable` for non-ready profiles.
No database call is made to obtain stats during animation.

Toggles are accepted only while the profile and preferences are ready and no save
is pending. Confirmation follows persistence; UI changes on the shared task
(normally within five ticks). A caller timeout does not cancel the save. A late
successful save still updates the active viewer, while a failed/uncertain save
hides the displays and reloads authoritative preferences. `/display status`
retries a failed preference load; there is no unbounded retry loop. Database
failure never claims a toggle was saved.

## Configuration and safe reload

The bundled `config.yml` contains the complete `displays` section. Existing
runtime configurations inherit bundled defaults for omitted keys; add the section
to customize it. `displays.server` is an explicit 1-32 character identifier such
as `lobby-1`. No plugin/server reload is necessary for display-only settings:
edit the runtime config and run `/clubadmin displays reload`.

Reload reads YAML and validates the full display configuration off-thread before
replacing it on the primary server thread. Invalid YAML, types, enum values,
markup, placeholders or limits preserve the prior working configuration. A
timed-out reload is not applied later. Reloading displays does **not** reload
database, profile or admin-amount settings. A disconnected administrator does not
prevent a valid reload from being applied.

| Setting | Default | Validation / behavior |
|---|---|---|
| `enabled` | true | Master display switch; does not erase personal preferences. |
| `server` | `lobby-1` | Letters, digits, underscore or hyphen, 1-32 characters. |
| `bossbar.enabled` | true | Independent bossbar switch. |
| `bossbar.update-ticks` | 10 | 5-72,000 ticks; checked on the shared five-tick task. |
| `bossbar.rotation-ticks` | 100 | 5-72,000 ticks; rotates configured announcements. |
| `bossbar.color` | `PURPLE` | Adventure discrete color, not an RGB fill. |
| `bossbar.overlay` | `PROGRESS` | Adventure overlay enum. |
| `bossbar.progress` | 1.0 | Finite number in 0..1. |
| `bossbar.messages` | 3 bundled messages | 1-32 frames, at most 256 rendered characters each. |
| `scoreboard.enabled` | true | Independent sidebar switch. |
| `scoreboard.title-animation-ticks` | 10 | 5-72,000 ticks. |
| `scoreboard.stats-refresh-ticks` | 20 | 5-72,000 ticks; cached stats only. |
| `scoreboard.title-frames` | 2 bundled titles | 1-32 frames, at most 64 rendered characters. |
| `scoreboard.lines` | 7 bundled rows | 0-15 rows, at most 128 rendered characters per row. |

Intervals are quantized by the five-tick scheduler. Input strings are bounded
to 1,024 characters without control characters. Length validation accounts for
maximum placeholder sizes. Configuration is admin-managed; player input is never
deserialized as MiniMessage markup.

Supported MiniMessage tags are colors, decorations, gradients and rainbow.
Strict parsing requires explicit closing tags, for example:

```yaml
messages:
  - "<light_purple><bold>AFTERPARTY</bold></light_purple>"
  - "<aqua>Welcome, {username}!</aqua>"
```

Click/hover actions, selectors, arbitrary tags and unknown placeholders are
rejected. Only `{level}`, `{coins}`, `{xp}`, `{username}`, `{online}` and `{server}`
are supported. Substituted values are literal text; numbers use locale-independent
grouping (`2,450`). Each viewer's render uses one immutable profile snapshot.

## Ownership and cleanup

**Selected policy: ClubCore owns the lobby sidebar.** If another plugin replaces
the player's scoreboard while the lobby display is active, ClubCore restores its
own board on the next update. Do not enable competing lobby scoreboard plugins.

Future games can call `plugin.displays().suppress(uuid, true)` on the server
thread **before** claiming a scoreboard. This releases the bossbar/sidebar;
`suppress(uuid, false)` returns ownership to the lobby. Suppression is temporary,
not a saved player preference.

Quitting, toggling off, disabling the feature, config replacement and plugin
disable remove bars and unregister owned objectives. The previous scoreboard is
restored only if the player is still displaying ClubCore's board, so cleanup
does not overwrite a newer owner's board. One task is cancelled on disable, and
viewer/dirty/completion references are cleared. Login tokens prevent late
preference loads or saves from applying to a replacement viewer.

Sidebar entries use stable internal identities with Paper component score names
and blank number formatting, rather than rendered text as an identity. Duplicate
and blank rows therefore coexist. Only changed titles/rows are sent; ordinary
animation does not recreate boards or bars. Profile session transitions enqueue
dirty UUIDs, allowing committed XP/coin refreshes to appear without reconnecting.
Periodic cached-stat refresh also updates online count. `lastUpdateNanos()`
exposes the latest approximate shared-update cost for diagnostics.

## Verification and release gates

The November 9 clean build passed 187 tests, including 25 display tests, with no
failures, errors or skips. See [v0.2 release notes](release-notes-0.2.0.md) for the
combined feature scope and backup-first upgrade procedure.

Automated coverage includes config validation, animation boundaries, placeholder
formatting/fallbacks, V2-to-V3 and fresh migrations, independent preference flags,
restart persistence, timeout/rejoin ordering, atomic reload behavior, sidebar
ownership, 20 join/quit cycles and 100 mocked viewers sharing one task. Mock
viewer tests are **not** proof of live rendering, TPS or packet behavior.

Before production:

- Back up and verify restore **before** applying V3; old jars do not undo it.
- Test actual Paper 26.3 clients: animation legibility, no flicker, duplicate rows,
  permissions, multiple players, live grants and restart-persistent toggles.
- Test valid/invalid reloads, database outages, quit/rejoin during saves,
  suppression/foreign-board handoff, and plugin disable.
- Measure TPS, memory retention and update cost under realistic player load.

No live database or Neon branch was modified during this implementation.
