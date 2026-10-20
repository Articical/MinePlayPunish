# Verification record — 20 October 2026

This record describes checks actually performed. It does not certify production readiness.

## Build and automated tests

- Windows 11, Eclipse Temurin Java **25.0.4.1+1**.
- Gradle wrapper **9.8.1**, compile API **Paper 26.3.build.200-stable**.
- Final command: `gradlew.bat formatJava build --offline --no-daemon --console=plain --warning-mode=fail` (following a clean build).
- **BUILD SUCCESSFUL**. Java source compilation uses `-Xlint:deprecation`,
  `-Xlint:removal` and `-Werror`. These checks cover this source, not dependency internals.
- **443 tests, 0 failures, 0 errors, 0 skipped**.

| Area | Tests | Evidence |
| --- | ---: | --- |
| Command grammar, dispatch and tab completion | 53 | Quotes/flags, required reasons, limits, no argument-file expansion, explicit issue/add, early release, prison and quarry operation dispatch, nested/prefix/namespaced completions, permission gates and visible targets |
| Staff GUI authorization gateway | 27 | Individual permissions, expiry/deadline, final hierarchy checks, cancellation during lookup, connection identity, main-thread dispatch, reason bounds, immutable retry actions, bounded cache, service limits/tool/readiness rechecks and post-commit failure handling |
| Punishment menu controller | 109 | All time/block presets, custom block bounds/overflow/input validation, fixed preset tamper protection, configurable lists, explicit confirmation, private reason handoff, deferred transitions, inventory controls, stale/cross-player callbacks, duplicate clicks and lifecycle cancellation |
| Configuration | 7 | Defaults, HTTPS origin, database path, unsafe flags, limits, existing messages.yml filter feedback fallback and customization |
| Public chat filter | 53 | Unicode whole-word/phrase matching, safe regex syntax and compilation bounds, adversarial patterns, concurrency, cancellation, original/current components, main-thread feedback, atomic reload and rollback |
| Chat/connection enforcement listeners | 5 | Cached mute, namespaced aliases, storage failure, final connection validation |
| SQLite/domain | 35 | Migrations, FULL/WAL/FK, rollback, expiry, revocation, warnings, kicks, identity, replay, revisions, restart/cache health |
| Community Service | 123 | Real SQLite snapshot/progress/restart/recovery, concurrent same-coordinate claims, mining/drop/XP checks, separate prison bounds and quarry validation, complete/unready layout protection, admission and recovery callbacks, empty-slot restoration, idempotent completion, bounded NBT receipts including corrupt gzip |
| HTTPS sync/API contract | 31 | Mock transport timeouts/auth/outage, durable inbox/outbox/acks, replay/conflicts, appeal rotation races, pagination, shutdown, YAML refs |

Local detailed results are in `build/reports/tests/test/index.html` and
`build/test-results/test/`. The tests use real SQLite files, deterministic clocks,
mock HTTPS transports and mocked Paper player/event objects as appropriate.
They do not simulate every behavior of a live Minecraft client or third-party plugin.

## Paper staging smoke checks

With the user's EULA approval, started an isolated **Paper 26.3 build 205** server
on **127.0.0.1:25585**, with its own world, database and `mineplay-local-staging`
server ID. Website sync was disabled and a staging-only nonexistent credential
environment variable was selected. No production service was contacted.

The bundled deployment JAR loaded and enabled on the first run and after restart.
Console tests verified:

- Permanent BAN and MUTE commits.
- Five-second BAN and MUTE transition to EXPIRED and no longer appear active.
- MUTE revocation persists as REVOKED.
- Offline WARNING remains PENDING and unacknowledged.
- Offline KICK records FAILED delivery, with a historical COMPLETED punishment.
- A second active ban for the same target is rejected without a duplicate record.
- Unknown username and missing reason are rejected.
- Unconfigured quarry rejects Community Service assignments and validation reports incomplete setup.
- Status, manual sync queueing, history and valid reload execute.
- Invalid HTTP appeal URL makes reload fail; the previous configuration and active ban remain usable.
- Restart retains the same permanent-ban reference and all expiration/revocation states.
- Clean save and shutdown finish. The staging server is stopped after verification.

Read-only SQLite assertions after shutdown verify integrity `ok`, WAL mode, no
foreign-key violations, six expected punishment records, FAILED offline kick,
PENDING unacknowledged warning, zero unconfigured service sentences and **10 retained
outbox events**. Run `python scripts/verify-staging-store.py` to repeat these assertions
against the labelled local staging fixtures only. Raw local evidence is retained in
`verification/staging-first-run.log`, `verification/staging-final-run.log`,
`verification/staging-store.json`, and `verification/test-summary.json` (ignored by Git).

Paper emitted a Windows performance-counter/OSHI warning unrelated to this plugin,
and an initial flat-world generator-settings warning. Neither prevented startup.
No MineplayPunishments startup error occurred in either run.

## GUI runtime construction checks

The previous 1.0.0 GUI build was loaded on 18 October on the same isolated
**Paper 26.3 build 205** server.
A temporary verification plugin invoked the production renderer using a proxy player
that captured screen transport calls, while actual Bukkit inventory creation and
Paper native dialog conversion ran on the server thread. The probe passed **465
assertions across 33 cases**, covering:

- Namespaced `/punish` registration, executor and tab completer; console refusal with
  no change to the punishment count.
- Actual console/player quarry and prison tab-completion responses, including the namespaced
  alias and partial operation prefixes, without invoking any world-editing operation.
- The production player-file resolver matches actual Paper player storage at
  `<level directory>/players/data`. A fresh unregistered synthetic player wrote a
  real Bukkit PDC receipt through `Player.saveData()`. The production verifier read
  it successfully, while the obsolete dimension-folder lookup failed. Its generated
  player file was removed after evidence capture; no real player was involved.
- The 27-slot inventory, its custom holder, item labels and target identity lore.
- All seven duration choices for both Ban and Mute, plus Kick and Warn.
- All six Community Service block presets, and the Custom form with separate block
  and reason inputs (reason limits of 1, 1000 and 4000).
- Native confirmation buttons, reason input, distinct callbacks, Escape's cancel
  action mapping, configured reason limits of 1 and 4000, and explicit dialog close.

All **27 dialog variants** constructed and converted successfully. This checks server
API behavior; it does **not** test client rendering, real inventory clicks, custom-click
network packets, or the full flow with an actual logged-in staff member. Controller
and gateway tests cover the mocked interaction/authorization paths separately.

The server stopped cleanly. The helper JAR was removed from staging's `plugins/`
directory and is not part of the deployment artifact. Read-only SQLite checks still
pass with the original six fixtures and 10 outbox events. Evidence is retained in
`verification/staging-prison-release-run.log`, `verification/prison-release-smoke-result.json`, and the
repeatable helper source/build instructions under `verification/gui-smoke/`.

## Public chat filter runtime checks

The 1.1.0 deployment JAR loaded on the same isolated Paper server on 20 October.
A separate staging-only helper exercised real `AsyncChatEvent` dispatch from an
asynchronous scheduler thread using a synthetic player. It verified normal chat,
whole-word boundaries, case/full-width/invisible-character variants, regex matching,
and a message rewritten between the early and late handlers. Cancellation retained
the renderer, viewers and original message; already cancelled messages received no
duplicate notification. All five rejection messages ran on the main thread.

The helper performed a real valid settings reload, then an invalid regex reload,
and confirmed that the previous filter remained active. It restored the original
staging configuration byte-for-byte. There were **49 event/lifecycle assertions**
and **6,000 matching assertions** in the timing loops, all passing.

Local warmed-loop measurements of one engine invocation averaged **0.702 ms** for
a 73-character clean message against 100 literal rules plus two regex rules, and
**0.486 ms** for a 4,096-character nonmatch against `(a+)+$`. These include reflection
and assertion overhead. The listener normally checks at both early and late event
priorities and can check both original and rewritten text. These figures describe
this machine and workload, not a server-load guarantee; normal client chat matching
runs off the main tick thread.

Evidence: `verification/chat-filter-build.log`,
`verification/staging-chat-filter-run.log`,
`verification/chat-filter-smoke-result.json`, and the repeatable helper in
`verification/chat-smoke/`. The server stopped cleanly; the helper JAR was removed.
The SQLite fixture checks still pass with six punishments and ten retained outbox
events. No real players, clients, Discord bridge or proxy participated.

## Artifact

`build/libs/MineplayPunishments-1.1.0.jar`

Replace the installed old plugin JAR and fully restart Paper. Install only one copy.
Earlier versioned artifacts are retained locally for reference.

SHA-256: `ccc131fea8c306d5c75e03501080f5db3ba8f09f29721cfbfbdacf41268fe032`

The deployment artifact contains plugin metadata, configuration, messages, schema,
plugin classes and bundled runtime dependencies, including relocated RE2/J 1.8.
Paper/Bukkit, Adventure and
LuckPerms server classes must remain external.

## Still required before production

Complete [the acceptance checklist](staging-acceptance.md) with real players and the
actual LuckPerms/WorldGuard/chat/economy/proxy stack. In particular, actual quarry
mining/release with real client inventories, disconnect/crash injection while a
real inventory is held, and plugin-specific messaging/economy interactions have not
been exercised by clients on this staging server. The synthetic receipt check above
verifies the exact Paper save format and path, not Mogamary's live recovery.

The punishment menu also needs the documented real-client visual and interaction
checks, including native reason entry, cancellation, item movement attempts and
issuing a punishment while staff permissions change.

The public chat filter requires real-client delivery checks with the installed
chat plugins and Discord/proxy relays. Native-event cancellation cannot retract an
earlier external relay or control another plugin that ignores cancellation.

The ASP.NET endpoint implementation, real HTTPS credentials, backend token issuance,
remote staff role authorization and end-to-end appeal processing have not been
tested against the live website. The supplied OpenAPI contract and fixtures define
that separate integration work. Until then, appeal presentation uses the explicit
pending-provisioning fallback.
