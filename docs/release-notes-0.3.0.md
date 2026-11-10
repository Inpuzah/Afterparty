# ClubCore v0.3.0 candidate - Player settings

Not a production-approved release. See the
[manual acceptance record](testing/settings-manual-acceptance.md).

## Changes

- Immutable `PlayerSettings` and asynchronous `PlayerSettingsService`.
- Player-only `/settings`, permission `club.settings` (default everyone).
- Persistent inventory toggles for bossbar/sidebar, confirmed/pending/error
  states, informational slot and deferred close.
- One cache and V3 persistence path shared with `/display` and lobby displays.
- Inventory manipulation protection, rapid-click reservation and exact
  player/session/holder checks for deferred work and callbacks.
- Ordered uncertain-write recovery, caller cancellation/timeout isolation and
  continued persistence after menu close.

No social/privacy controls, no new configuration, no V4 and no changes to
historical migrations. Existing V3 choices and server/ownership policies remain.
The display engine retains one shared five-tick animation task.

## Verification

Build with Java 25: `./gradlew clean build`. Deployable candidate:
`core/build/libs/ClubCore-0.3.0.jar`.

Automated coverage includes settings/menu lifecycle, existing display behavior,
V3 defaults/upgrade reuse, independent flags, actual PostgreSQL restart,
service recreation, injected SQL rejection/rollback and reconnect ordering.
GUI tests inject an inventory renderer; item appearance and real-client behavior
remain staging requirements.

Local Java 25 verification: `clean build --console=plain` completed
`BUILD SUCCESSFUL in 1m 17s`; JUnit XML reports **209 tests, 0 failures, 0 errors,
0 skipped**. Jar inspection confirmed version 0.3.0, `/settings`, 12 settings
classes, unchanged V1/V2/V3 migrations and all 42 merged Flyway providers.

SHA-256 of that local candidate jar (not a signed/published release):

```text
8A818E879CDAA46FFAEA2316D2096301170371B9051B0F3D54781A30643BC65E
```

Remote CI, live Paper/client checks, performance and backup/restore gates remain
open. The local build does not migrate a live database or validate Neon runtime
integration.

## Upgrade and rollback

1. Back up the staging/production database and runtime config; test restoring
   the backup before deployment.
2. Verify Java 25, Paper 26.3, the jar checksum and candidate CI/test results.
3. Stop Paper, replace the jar and start against an isolated staging copy first.
4. Complete all [manual acceptance checks](testing/settings-manual-acceptance.md).

Upgrading from v0.2 requires no new migration. Earlier installs apply V1 through
V3 as usual and need the existing migration privileges. Do not edit migration
history. Code rollback to the v0.2 jar retains the same V3 choices; restore the
backup only through the rehearsed, stopped-server restore procedure if needed.
