# 0002 - Reuse V3 for unified player settings

Status: accepted for the v0.3 implementation candidate.

## Context

V3 already stores the two implemented choices, bossbar and sidebar visibility,
by UUID. A settings GUI must not create another source of truth or reset existing
preferences. Social/privacy features have no runtime implementation yet.

## Decision

Keep V1, V2 and V3 unchanged and add no V4. `PlayerSettings` is an immutable
domain snapshot of the existing flags. `DefaultPlayerSettingsService` owns one
online settings cache and wraps the existing ordered, bounded preference service
and JDBC repository. `/display`, `/settings` and display rendering share it.

Accept one mutation per active player settings session. On uncertain writes,
retain the confirmed snapshot for error UI and reload from storage before another
mutation. Isolate caller timeouts/cancellation, guard replacement sessions by
entry identity, and use main-thread, token/holder-checked menu callbacks.

## Consequences

Upgrades retain existing choices without data movement or new schema privileges.
The v0.2 jar can read the same V3 data if code rollback is necessary; never undo
applied migrations. Clean installs still apply V1 through V3. Storage classes
retain their display-oriented names as an internal adapter rather than duplicate
the working repository.

Future implemented settings may require a new migration and typed fields, but
there are no speculative social/privacy columns or active controls in this release.
Multi-server settings invalidation is outside this single-server milestone.
