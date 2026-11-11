# Player telemetry publisher

MineplayPunishments publishes observations, introduced in 1.3.0, to
`POST https://www.mineplay.co.uk/api/moderation/server/players`. It reuses
`server.id`, the existing bearer credential environment variable, HTTPS transport
and sync worker. Headers include `Content-Type: application/json`, `X-Server-Id`
and `X-Request-Id`; envelope IDs match the headers and `schemaVersion` is 1.
No telemetry is submitted to punishment `/events`.

## Install and configure

Replace the old plugin JAR with `MineplayPunishments-1.4.1.jar`, retain the plugin
data directory and restart Paper. Install only one MineplayPunishments JAR. Existing
configurations receive these defaults without needing their files replaced:

```yaml
player-telemetry:
  enabled: true
  refresh-seconds: 30
  players-per-tick: 5
```

Version 1.4.1 fixes a collector exception when two queued observations both have
unknown login counts or playtime. It preserves those unknown values as null and
requires no database reset. See the [regression record](telemetry-null-fix-verification.md).

Both `website.enabled` and `player-telemetry.enabled` must be true. The refresh
interval accepts 10–60 seconds; collection accepts 1–100 players per tick. Bukkit
and Vault reads run on the main server thread. Player-file discovery/history reads,
SQLite work and HTTP run on workers. Collection and backfill are bounded so a large
historical roster is processed incrementally.

Vault and an economy provider are optional. Their absence does not stop roster or
session publication. The plugin bundles neither Vault nor its economy provider.
Use `/mp admin status` for sync/telemetry diagnostics and `/mp admin reload` after
changing settings or repairing historical data. Reload does not end gameplay sessions.

## What is observed

- All valid UUID player files in Paper's level `players/data` directory are scanned,
  including players with no punishment records. Connected players are refreshed,
  and join/quit events queue observations immediately.
- First/latest joins come from actual saved `bukkit.firstPlayed` and `Paper.LastLogin`
  fields or a genuine new-player join. File timestamps, directory reads and punishment
  dates are never substitutes. Missing/corrupt join history is skipped with a count
  diagnostic; the required website fields cannot truthfully be filled with guesses.
- A latest logout is recorded from a real quit or a confirmed server shutdown.
  Paper's `LastSeen` can be written during autosave and is not treated as a logout.
  Historical logout is null until known. Crashes do not invent a closing timestamp.
- Playtime comes from the play-time statistic, converted from ticks to whole seconds.
  Unavailable history remains null. Login count is known only when observation began
  with the player's first join; otherwise it remains null. Known counters survive
  restart and missing later source values.
- Sessions use stable UUIDs scoped to server, player and genuine join time. Refresh
  and reload retain the same session ID. A closed session is never reopened or given
  another logout time. The newest 1,000 sessions are retained locally and delivered
  in pages of at most 100, including closures that happened during an HTTP retry.
- Only resolved literal addresses from a player's server connection are captured.
  IPv4-mapped IPv6 is normalized to IPv4; at most five distinct recent addresses and
  their observation times are retained. No hostname lookup is performed.

The offline roster is observed at startup and reload. Periodic refreshes cover online
players. The website marks online observations stale after two minutes; outages,
rate limiting or unusually large workloads can delay freshness. Plugin disable alone
does not claim that connected players logged out.

## Economy wire format

`AVAILABLE` requires a provider name, the provider's game-currency label and a balance
encoded as a **JSON string**, such as `"1250.500000"`. Conversion uses a plain decimal
with at most 18 integral and six fractional digits. Negative and zero balances are
supported. Non-finite, out-of-range and excess-precision values are unavailable;
balances are never silently rounded to fit the endpoint.

`NOT_INSTALLED` means Vault is absent. `UNAVAILABLE` covers no registered/usable
economy provider or an invalid observation. `NO_ACCOUNT` means the provider reports
that this player has no account. These three states send a null balance.

## Persistence, retries and limits

Private `telemetry_*` SQLite tables store player state, session/address history and
one frozen pending snapshot per player. They do not create punishment identities,
immutable punishment audit events or moderation inbox/outbox entries. Keep this
database and its backups private: local telemetry contains network addresses;
website encryption does not encrypt the plugin database.

Revisions increase persistently per server/player and remain below Int64.MaxValue.
Each changed observation gets a newer revision. The pending JSON is frozen before
HTTP; a lost response retries the same player item while newer state waits separately.
An acknowledgement applies only to the matching pending revision, so it cannot erase
a newer observation or an intervening session closure. Session-only continuation
pages also receive newer revisions. UTC timestamps end in `Z`.

Requests contain 1–100 distinct players and stay strictly below 1 MiB, including
the UTF-8 envelope. All moderation requests share 350 ms pacing, leaving headroom
under the 240 requests/minute/IP limit. Other processes sharing the same public IP
still consume that website allowance. `Retry-After` on 429/503 delays all client
lanes, and transient failures use capped exponential backoff. Authentication
failures use the existing longer pause. Waiting and routine successful polling do
not produce console spam.

Both `acceptedPlayerIds` and `ignoredPlayerIds` must be valid, disjoint and cover
the submitted batch exactly before anything is acknowledged locally. An older
ignored revision is retired; the latest local state still advances independently.

A 400/409 rejects the entire website transaction. The publisher retries the frozen
items individually to isolate the offender, then quarantines only the rejected
revision with a controlled category. Other players continue. Repair the source
history and reload to collect a newer revision; do not delete revision state or
alter a frozen JSON snapshot. Known conflict codes are `PLAYER_REVISION_CONFLICT`,
`PLAYER_OBSERVATION_CONFLICT`, `PLAYER_TIMELINE_CONFLICT` and `PLAYER_SESSION_CONFLICT`.
Unknown errors use a fixed HTTP category. Logs never include bearer headers, raw
telemetry/error bodies, balances or IP histories.

Persistence failures retain pending observations for retry while collection pauses
under backpressure. A prolonged database failure or abrupt process crash can lose
observations that have not yet committed; missing sessions/logouts are not fabricated.
The committed snapshot/revision remains retryable across restart.

## Contract and validation

The supplied endpoint contract is represented in [OpenAPI](api/openapi.yaml),
[request fixture](api/examples/players-request.json) and
[response fixture](api/examples/players-response.json). Fixtures are illustrative
and must never be sent to production. The website owns staff permissions, MFA,
network-access audit logging and encryption of its five-address history.

Automated checks and isolated Paper checks are recorded in [telemetry verification](telemetry-verification.md).
Production HTTPS delivery, website database/encryption, a real Vault provider and
multi-client gameplay require validation with the deployed stack.
