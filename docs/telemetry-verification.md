# Player telemetry verification — 9 November 2026

## Build and automated tests

The final 1.3.0 build used Temurin Java 25.0.4.1+1, Gradle 9.8.1 and the existing
Paper 26.3.build.200-stable compile API. Optional VaultAPI 1.7 is compile/test-only,
with its obsolete Bukkit transitive dependency excluded.

Command: `gradlew.bat formatJava build --offline --no-daemon --console=plain --warning-mode=fail`.
Result: **BUILD SUCCESSFUL; 1,612 tests, zero failures, errors or skipped tests**.
This includes the previous 1,446-test suite and 166 added cases for telemetry and
reload integration. The first full run exposed missing/new Mockito fixture setup;
those fixtures were corrected before the final successful run. The reload integration
also keeps chat-filter publication last, after telemetry reconfiguration succeeds.

Checks cover:

- Real SQLite persistence, increasing revisions and exact frozen retries across restart.
- Concurrent observations and session closure during HTTP/acknowledgement, session
  pagination/retention, five-address normalization and fair roster scheduling.
- Known/unknown counters, genuine historical fields, malformed/bounded NBT/statistics,
  main-thread collection, pending history ordering, reload and shutdown handling.
- Vault absent/unavailable/no-account/available states, exact decimal strings,
  non-finite/out-of-range/excess-precision values and provider/currency validation.
- `/players` headers/envelopes, complete accepted/ignored partitions, malformed or
  lost responses, 400/409 isolation, shared pacing, Retry-After seconds/HTTP dates,
  cancellation and independence from moderation polling.
- Existing chat-filter reload success and rollback, including telemetry failure.
- OpenAPI YAML/reference resolution, including the additive `/players` schemas.

HTTP tests use injected transports and clocks. Vault tests use a mocked provider;
they do not contact production or prove a specific economy plugin's behaviour.

## Artifact

Deploy `build/libs/MineplayPunishments-1.3.0.jar`, not the unbundled or sources JAR.
SHA-256: `45ca92ad35c7424220f0edb670a958bc49eae6911f8d7f4e9956f9f8057259cb`.
The bundled artifact contains the telemetry schema and SQLite service descriptor,
with no bundled Bukkit/Paper, Adventure, LuckPerms or Vault API classes.

Local evidence is retained in `verification/telemetry-1.3.0-build.log`,
`verification/telemetry-test-summary.json`, `verification/telemetry-artifact.json`
and `build/reports/tests/test/index.html`.

## Isolated Paper backfill and restart

The exact final JAR passed two boots on Paper 26.3 build 205, bound to
`127.0.0.1:25585`, with no clients or Vault plugin. Three temporary synthetic player
files were placed only in the disposable staging world: two with verified first/latest
join fields and one without a known latest join. One had saved playtime; the other
had no statistics. A separate SQLite file isolated this run from earlier moderation
fixtures.

Both valid historical players appeared without punishment records, including a
Bedrock-style name. The incomplete player was skipped with a count diagnostic.
The test confirmed genuine historical dates, `online: false`, unknown logout/login
count as null, correct known playtime, missing playtime as null and Vault status
`NOT_INSTALLED` with a null balance. After restart, both revisions increased while
their unacknowledged frozen snapshots stayed byte-identical. SQLite integrity and
foreign-key checks passed, and no punishment outbox events were created.

The API origin was an isolated `.invalid` hostname with a deliberately absent
credential environment variable. No fixture or credential was sent to production.
The server stopped cleanly, the original staging configuration was restored
byte-for-byte and temporary player/statistic files were removed. The isolated
telemetry database remains as evidence.

Evidence: `verification/telemetry-smoke/first.log`, `restart.log`, `result.json`
and the repeatable `verification/run-telemetry-smoke.py` runner. This validates
actual Paper collection and optional-dependency loading, not a real website response
or real-player session flow.

## Remaining validation

The deployed website, real bearer credentials, PostgreSQL telemetry migration,
encryption/MFA/auditing, real players and an installed Vault economy provider have
not been exercised here. Complete the [staging checklist](staging-acceptance.md)
against that actual stack before treating local checks as end-to-end evidence.
