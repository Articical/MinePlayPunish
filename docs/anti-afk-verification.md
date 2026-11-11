# Anti-AFK verification — 10 November 2026

## Build and automated tests

Version 1.4.0 was built with Temurin Java 25.0.4.1+1, Gradle 9.8.1 and the existing
Paper 26.3.build.200-stable compile API. Command:

```text
gradlew.bat formatJava build --offline --no-daemon --console=plain --warning-mode=fail
```

Result: **BUILD SUCCESSFUL; 1,699 tests, zero failures, errors or skipped tests**.
This includes 78 focused anti-AFK cases and nine additional integration cases
for configuration reload rollback, existing message files and prison command guards.
The first build exposed two new test assertions using the removed Adventure
`ClickEvent.value()` accessor. They were updated for the target API before the
successful full run. Production compilation passed on both runs.

Checks cover default/configured timing, monotonic clock wraparound, bounded
histories, passive movement, controls against walls, short movement circuits,
distinct/repeated blocks and chat, single-use connection-bound codes, delayed
warning grace, shorter/longer reloads, reconnect isolation, dynamic bypass changes,
cancelled kicks and bounded error logging. Adapter tests use mocked Bukkit players
and events; pure tracker tests use deterministic clocks. Existing moderation,
prison, chat-filter, SQLite and telemetry tests also pass.

## Artifact

Deploy `build/libs/MineplayPunishments-1.4.0.jar`, not the unbundled or sources JAR.
SHA-256: `d7f0bdec78c1148db8a7b41b73cc3142699b8988a1e3e7ccd3c764cca700e7a9`.

Inspection confirmed version/command/permission metadata, the three anti-AFK
classes, the SQLite service descriptor and no bundled Paper/Bukkit, Adventure,
LuckPerms or Vault classes. Evidence is retained locally in
`verification/anti-afk-1.4.0-build.log`, `anti-afk-test-summary.json`,
`anti-afk-artifact.json` and `build/reports/tests/test/index.html`.

## Isolated Paper runtime

The exact final artifact passed **66 assertions** on Paper 26.3 build 205,
bound to `127.0.0.1:25585`, with no connected clients and website sync disabled.
The probe verified real plugin startup, the namespaced public confirmation command,
operator bypass default, Adventure message fallbacks, scheduler/listener lifecycle,
idempotent start, disable/re-enable/stop cleanup, absence of an anti-AFK movement
listener and precise custody command exemptions.

The loaded tracker was exercised with a deterministic clock for warning timing,
code replay/expiry, delayed checks, shorter reload grace, cancelled kicks, passive
motion and repeated movement/block activity. Synthetic disconnected player proxies
verified fresh connection replacement, late quit isolation and rejection by the
real Paper player lookup before any kick could occur. They were not inserted into
Paper's live player list. The probe temporarily used an in-memory synthetic custody
transition and restored it, along with the original command executor/completer.

The first probe reached the custody guard before its synthetic player lacked a
`getServer()` accessor required by Paper's event constructor. Adding that accessor
to the helper allowed the second run to pass; production code did not change.
Both logs are retained under `verification/afk-smoke/`. Final evidence is
`paper.log` and `result.json`; the repeatable runner is
`verification/run-afk-smoke.py`.

The server stopped cleanly, its configuration remained byte-identical and the
helper JAR was removed from `run/plugins/`. The production 1.4.0 artifact remains
in the stopped staging directory; the previous staged 1.3.0 JAR was backed up.

## Validation boundary

No connected Minecraft client, real farm machine, actual network kick, resulting
telemetry logout or production load was exercised. The low-overhead claim is based
on one sparse player pass, bounded histories and absence of per-movement, world-scan,
database or HTTP work; no production TPS or latency number was measured.

Complete the [in-game acceptance checks](anti-afk.md#in-game-acceptance-checks) with
the server's actual machines, chat plugins, protections and command blockers.
Large circuits, varied client automation and automatic reconnects remain documented
limits of this heuristic.
