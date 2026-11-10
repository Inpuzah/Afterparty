# Settings manual acceptance

Status: **NOT RUN / NOT RELEASE APPROVED**.

Automated tests use disposable PostgreSQL and Bukkit interface doubles. They do
not prove live inventory rendering, packet behavior, Paper linkage, TPS or backup
restoration. No live Neon database was migrated for this implementation.

## Baseline gates still open

The merged v0.2 baseline built successfully locally before this work. Its live
client display/command acceptance, realistic load measurements, verified staging
backup/restore and remote CI confirmation were not supplied. Development proceeds
with these blockers explicitly open, not as evidence of release readiness.

## Environment and evidence to record

- Local candidate SHA-256:
  `8A818E879CDAA46FFAEA2316D2096301170371B9051B0F3D54781A30643BC65E`.
  Source is the `feat/player-settings` implementation; staging
  deployment/source identification is still pending.
- Java 25 version and Paper 26.3 build: pending.
- Isolated staging database/backup/restore evidence: pending.
- Minecraft client versions, player count, timestamps and tester: pending.
- Screenshots, startup/shutdown logs, worker metrics and TPS: pending.
- Candidate GitHub Actions run/result: pending publication.

## Real-client checklist

- [ ] Start staging Paper with the shaded jar; no linkage or migration errors.
- [ ] Join a new UUID; `/display status`, bossbar/sidebar and `/settings` agree.
- [ ] Existing V3 false/true combinations survive upgrade unchanged.
- [ ] Toggle bossbar off, then sidebar off; each disappears after confirmed save.
- [ ] Re-enable both; correct cached stats and animations return without flicker.
- [ ] Toggle via `/display` while the GUI is open; it reflects committed choices.
- [ ] Close/reopen, reconnect and restart Paper; choices remain saved.
- [ ] Spam clicks during delayed writes; exactly one pending mutation, no stale UI.
- [ ] Close, disconnect/rejoin and reopen during a save; old callbacks cannot
      alter a new menu, but accepted persistence finishes.
- [ ] Shift/hotbar/offhand/double/drop/outside/bottom clicks and drags cannot move
      or duplicate menu/player items. Normal inventory works after closing.
- [ ] Test console, extra arguments, no permission and revoked permission.
- [ ] Test profile loading/unavailable and DB outages; no false success, confirmed
      choices survive, explicit error, successful recovery.
- [ ] Server-wide display disable/re-enable retains saved choices.
- [ ] Valid/invalid display reloads preserve preferences and safe open-menu state.
- [ ] Suppress lobby displays for another owner; menu toggles do not steal it.
- [ ] Multiple players share one animation task; no per-frame SQL or per-menu timers.
      Record TPS, update cost, worker queue and retained objects under realistic load.
- [ ] Quit/disable cleans menus, bossbars, objectives, workers and scheduled tasks.
- [ ] `/profile`, `/balance` and admin XP/coin operations still pass acceptance.
- [ ] Back up, restore and test code rollback on a disposable staging copy.

## Results

No live-client pass or failure evidence has been recorded. Attach evidence and
resolve every gate before declaring v0.3.0 released.
