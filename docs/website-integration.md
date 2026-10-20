# ASP.NET Core / PostgreSQL integration

The canonical website is **https://www.mineplay.co.uk**. Implement the server API in the existing ASP.NET Core application using [the OpenAPI 3.1 contract](api/openapi.yaml). This repository contains the Java client only. The endpoints have not been deployed or exercised against the live website as part of the source implementation.

## Authority and workflow

SQLite owns game enforcement, recovery snapshots and actual service progress. PostgreSQL owns website staff authorisation, appeal decisions and the durable remote command stream. They do not share a transaction. A website decision starts as `PENDING_ENFORCEMENT`; show `APPLIED` only after the plugin acknowledges its local commit and cache publication. Do not show a player as unbanned while the revocation still awaits that acknowledgement.

For community-service release, the commit only requests safe restoration. Show `RESTORATION_PENDING` until a completion/release event confirms restoration. Remote kicks similarly have separate `PENDING`, `EXECUTED`, `FAILED` and `EXPIRED` delivery states; an applied historical record does not prove a player was disconnected.

The plugin polls; it exposes no inbound HTTP listener or arbitrary-command execution endpoint. The backend authenticates staff before persisting a command and includes an immutable `staffId`, stable player UUID, action UUID, punishment UUID, UTC command lifetime and expected punishment revision. A supplied username is display metadata, never the identity key.

## PostgreSQL transactions and constraints

Suggested backend tables:

- `moderation_commands`: `(server_id, action_id)` unique; immutable action JSON/hash; target UUID; punishment UUID; expected revision; actor; decision time; expiry; monotonically increasing per-server stream position; enforcement outcome and acknowledgement time.
- `plugin_events`: `(server_id, event_id)` unique; envelope/hash; received time. Insert the event and update projections in one transaction. Repeated identical events return their ID as accepted. Reject changed content under an existing ID and alert staff.
- `server_sync_snapshots`: server ID; immutable high-water position; cursor lifetime and stream epoch. Cursors are opaque server-scoped values, not authorisation credentials.
- `appeal_provisions`: `(server_id, provisioning_id)` unique; punishment/player binding; token hash; encrypted recoverable token or protected exact URL; creation/rotation/revocation timestamps.
- `appeals`: punishment binding, authenticated applicant, decision actor/reason, decision state and the resulting remote action UUID.

Use PostgreSQL row locks or compare-and-update predicates for decision revisions. Separate the website decision revision from the last acknowledged enforcement revision. Never acknowledge a command by issuing another command or by merely returning it from `/actions`.

Store only a hash for token verification, but retain an encrypted recoverable value for repeat provisioning responses. Use ASP.NET Core Data Protection with persistent, access-controlled keys (or the site's established KMS). A lost response must not force the token to change. Do not use a punishment UUID as an appeal credential. Apply normal session authentication, CSRF protection, rate limits and ownership/staff authorisation to appeal forms; a bearer link identifies the punishment context and is sensitive even if further authentication is required.

## HTTP contract

The root is `https://www.mineplay.co.uk/api/moderation/server`. All successful responses are HTTP 200 JSON, include `schemaVersion: 1`, echo `X-Request-Id` as `requestId`, and echo the authenticated `serverId`. IDs are canonical UUID strings. Set `Content-Type: application/json`. No redirects are followed. Responses over 1 MiB, wrong request/server identities or unsupported schemas are rejected.

| Endpoint | Purpose |
| --- | --- |
| `GET /reconciliation?serverId=...&cursor=...&limit=...` | Startup replay including changes missed during downtime; additionally returns `snapshotRevision`. |
| `GET /actions?...` | Ongoing ordered remote command pages. |
| `POST /actions/acknowledgements` | Durable applied/rejected outcomes. Return `acceptedActionIds`. |
| `POST /events` | Durable plugin event batches. Return `acceptedEventIds`. |
| `POST /appeals/provision` | Register/retrieve a stable appeal URL using a durable `provisioningId`. |

For every GET, the query and `X-Server-Id` must match the identity granted by the bearer credential. For POST, check the body server identity too. Reject unauthorised server access before reading or mutating its command/event data. Use separate cryptographically strong credentials for each server, store credential hashes, and support an overlap period for rotation. Secrets come from the process environment and are read before each request. A running process generally cannot see later parent-shell environment changes: rotate by restarting with the new environment, or preload two environment variables and reload `credential-env` to select the new one. Revoke the old credential only after confirming the new one works.

The client uses connection/request timeouts, capped exponential retries and a longer pause for 401/403 or missing credentials. It retains local decisions and queues through every network failure. Unknown 4xx/schema errors pause and retry with an operator-visible category; they never become an unban. Error bodies, tokens and bearer values are not logged. Backend middleware must also redact Authorization, appeal URLs, and token-bearing request/response bodies. Mark provisioning responses `Cache-Control: no-store`.

## Action semantics

| Action | Allowed payload fields | Required fields / effect |
| --- | --- | --- |
| `CREATE_BAN`, `CREATE_MUTE`, `CREATE_WARNING` | `reason`, `expiresAt`, `appealUrl` | `reason`; absent/null expiry is permanent. URL, if supplied, must already be registered. |
| `CREATE_KICK` | `reason` | `reason`; plugin clamps command lifetime to configured maximum (default 30 seconds). Offline/stale delivery does not wait for a future login. |
| `CREATE_COMMUNITY_SERVICE` | `reason`, `blocks`, `tool`, `appealUrl` | `reason`, `blocks`; requires a safely configured quarry. Tool is wooden or stone pickaxe. |
| `REVOKE_PUNISHMENT`, `RELEASE_SERVICE` | `reason` | Safe service restoration remains pending until confirmed. |
| `MODIFY_EXPIRATION` | `reason`, `expiresAt` | `expiresAt` required; null makes permanent. A past time expires the record. |
| `ADD_SERVICE_BLOCKS` | `reason`, `blocks` | Explicit audited increase. |
| `SET_SERVICE_BLOCKS` | `reason`, `blocks` | Explicit total requirement, including appeal reductions; no subtraction from completed work. |
| `ROTATE_APPEAL_TOKEN` | `appealUrl` | A newly backend-registered URL is required for a remote command. |

Creation expects no revision, null or zero. Every mutation requires `expectedRevision` equal to the current local revision and must address the correct punishment and player. A rejected conflicting action is a final outcome for that action UUID. Reconcile the decision, obtain the correct revision and issue a **new** action UUID; do not edit or replay the old command. An appeal denial produces no mutation. Accepting an appeal queues `REVOKE_PUNISHMENT`, or an explicit duration/work reduction if that is the decision.

All actions require `validUntil`, after `issuedAt`. Expired instructions are rejected durably. Order dependent commands in the backend stream and do not create a follow-up using a guessed future revision. Remote assignment is rejected when the quarry is unavailable; retry with a new action only after staff fix configuration.

## Crash and replay behaviour

For each page, the plugin writes the action outcomes, accepted mutations, audit/outbox events, acknowledgement queue and cursor in one SQLite transaction. It then publishes its enforcement cache before sending acknowledgements. If any local write fails, the page rolls back and its cursor does not advance. Invalid but addressable actions are persisted as rejections; an invalid action UUID or an altered duplicate causes the entire page to fail for explicit reconciliation.

The backend must retain the immutable command stream independently of delivery acknowledgements. A page lost in transit may be returned again. A locally committed page whose acknowledgement was lost is recovered using `remote_acknowledgements`; posting a repeated outcome must be idempotent. The client never resets an unknown/expired cursor automatically. On startup, `/reconciliation` must include all still-unseen revocations and modifications and return a stable high-water `snapshotRevision`; reaching `hasMore:false` confirms coverage through that mark. Keep cursor retention longer than any supported server downtime, or provide an operator-led recovery procedure. Do not substitute an unversioned active-punishment list and overwrite local state.

`sync_outbox` retains failed events, retry count, attempt time and next-attempt time. A successful HTTP response followed by a local crash can cause duplicate delivery. Backend acceptance must be durable before acknowledging IDs. Partial acceptance is supported; unaccepted IDs remain pending. Keep event ingestion and command creation distinct to avoid echo loops. Community-service progress is batched by the plugin, not sent as one HTTP request per stone block.

## Appeal provisioning and outage fallback

The selected fallback is `PENDING_STAFF_RECOVERY`; this implementation does not maintain a pool of unassigned secret tokens. Every non-kick creation without a backend URL atomically queues `appeal_requests` with a stable provisioning UUID. During an outage, the punishment remains effective and the player sees the explicit pending-provisioning message and public punishment reference. No fabricated `/appeal/<token>` link is displayed.

On reconnection, the client publishes queued creation events, then asks `/appeals/provision` for the punishment/player binding. The backend generates at least 256 bits of cryptographic randomness and registers the URL before returning it. The plugin validates the configured HTTPS origin/path, requires a base64url token with at least 32 decoded bytes, and atomically attaches the exact URL using the current punishment revision. The same URL remains in SQLite across reconnects and restarts. It is shown only to the affected player and explicitly authorised staff; public history, audit and outbox data omit the secret.

A local staff rotation clears the displayed URL and queues a fresh provisioning UUID, so the pending fallback is used until the new registered URL arrives. A fresh provisioning UUID requests rotation; repeating the same one retrieves the same URL. Backend-initiated rotation should keep the old link usable until the plugin's rotation acknowledgement arrives, unless staff explicitly revoke it for security reasons. A stale provisioning response must not replace a URL already rotated by another committed action.

## Validation boundary

The automated suite uses real SQLite files, injected transport failures and a deterministic clock to cover durable duplicate handling, conflicting/out-of-order actions, rollback, expired kicks, offline service assignment, authentication rejection, timeout, queued-event recovery, accepted/denied appeals and registered-link privacy. It does not prove interoperability with deployed ASP.NET endpoints, a reverse proxy or production credentials. Run the staging checklist with the website developer before enabling live remote moderation.
