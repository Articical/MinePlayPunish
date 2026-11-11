# Telemetry null-counter fix — 11 November 2026

## Report and correction

The supplied production trace identifies MineplayPunishments 1.4.0 on Paper
26.3 build 188. `TelemetryCollector.finishLiveDrafts` coalesced queued player
observations and threw in `greater(Long, Long)` when both login counts were
unknown. The same defect affected unknown playtime values. The nested numeric
conditional expression implicitly unboxed a null `Long`.

Version 1.4.1 uses explicit null branches before the numeric comparison. Both
unknown values stay null; a known value survives a later unknown value; two known
values retain their maximum. No database schema, configuration, AFK setting or
telemetry wire-contract change is required. The Gradle resource task also tracks
the version as an explicit input so a version-only change refreshes `plugin.yml`.

## Reproduction and verification

Sixteen regression cases were added to `TelemetryCollectorTest`:

- Fourteen combinations cover independently unknown counters, both unknown,
  known zero, increasing/decreasing/equal values and the largest supported counter.
- Deferred join and quit observations resolve together under the normal five-player
  tick budget, retaining the logout and closed session while counters remain null.
- A pending database write forces a refresh and subsequent quit to coalesce;
  the latest logout must survive instead of being discarded by a caught exception.

Before the fix, the focused suite ran 33 tests with five failures, including the
reported `finishLiveDrafts → enqueue → merge → greater` exception path. The failure
log and XML report are retained locally. After the fix, the full command
`gradlew.bat formatJava build --offline --no-daemon --console=plain --warning-mode=fail`
passed **1,715 tests with zero failures, errors or skipped tests**. The toolchain
remains Java 25.0.4.1+1 / Gradle 9.8.1 / Paper API 26.3.build.200-stable.

Artifact: `build/libs/MineplayPunishments-1.4.1.jar`.
SHA-256: `86430e52bcf9c8610cecfdb66095661a954532c6b074549746d3131c306993f4`.
Artifact inspection confirmed the embedded 1.4.1 version, confirmation command,
AFK bypass permission, telemetry collector and SQLite service descriptor, with
no bundled Paper/Bukkit, Adventure, Vault or LuckPerms API classes.

Evidence: `verification/telemetry-null-1.4.1-before-fix.log`,
`telemetry-null-1.4.1-before-fix.xml`, `telemetry-null-1.4.1-build.log`,
`telemetry-null-1.4.1-artifact.json` and `telemetry-null-1.4.1-tests.json`.
These are deterministic collector/queue tests with mocked Bukkit players and
repository futures, plus the full existing suite. The hotfix was not deployed to
production or exercised with a connected client. The previous 1.4.0 isolated
Paper smoke evidence is historical, not a new 1.4.1 runtime claim.

## Separate Paper command-builder error

The supplied log also shows `ConcurrentModificationException` in Paper's async
command builder immediately after the first player connected during startup.
The telemetry patch does not establish a fix for that error.

MineplayPunishments has no runtime Brigadier tree mutation or command-send listener.
Read-only review of the accessible matching-version Parkour, Fishing and MobArena
sources found synchronous startup executor binding, not delayed tree registration;
Parkour's late readiness message follows other recovery work. Those local sources
were not hash-matched to the deployed JARs and do not identify another plugin as
the writer.

Local Paper build 205 bytecode has matching stack line numbers, with line 586
iterating a redirect's children during command-tree copying. Paper's
[command-building patch](https://github.com/PaperMC/Paper/blob/main/paper-server/patches/sources/net/minecraft/commands/Commands.java.patch)
and [Brigadier's child collection](https://github.com/Mojang/brigadier/blob/master/src/main/java/com/mojang/brigadier/tree/CommandNode.java)
support a concurrent tree-mutation interpretation. The exact production build188
binary and the component performing the mutation remain unverified. No matching
upstream fix was established, and upgrading specifically to205 is not a verified
remedy. A reconnect after startup completes is the next scoped check to distinguish
a startup-only race from a recurring join-time problem.
