# MineplayPunishments

Standalone moderation for **Paper 26.3 / Java 25**, package `cloud.mineplay.punishments`.
Bans, mutes, warnings, kicks and persistent quarry Community Service are stored in
SQLite before enforcement changes. The optional HTTPS client synchronizes with
**https://www.mineplay.co.uk**. The website is a separate ASP.NET Core/PostgreSQL project
and is **not** implemented here.

## Build and install

Install Java 25 and set `JAVA_HOME` to it. The Gradle wrapper verifies the distribution
checksum and downloads build dependencies on the first run:

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-25'
.\gradlew.bat clean build --no-daemon
```

Deploy **`build/libs/MineplayPunishments-1.2.1.jar`**, the bundled JAR, to `plugins/`
and restart Paper. Do not deploy the `-unbundled` or `-sources` JAR. SQLite JDBC,
Gson, RE2/J and the command parser are bundled; Paper/Adventure and LuckPerms are not.
LuckPerms is optional but needed to verify offline staff permissions without an explicit override.
The plugin has no dependency on LiteBans or Essentials moderation.

For Eclipse, import as an **Existing Gradle Project** using Buildship and Java 25.
IntelliJ can open `settings.gradle.kts` directly. `./gradlew formatJava` formats sources.

Java's SQLite native-library warning can be avoided by adding
`--enable-native-access=ALL-UNNAMED` to the server JVM options. The Gradle test/server
tasks already do this.

Read [configuration](src/main/resources/config.yml), [message templates](src/main/resources/messages.yml),
[commands and permissions](docs/commands.md), and the [quarry guide](docs/community-service.md).
The initial prison/quarry setup is unconfigured and cannot accept sentences until validated.
Do not give a new plugin production inventory custody until the
[staging acceptance checklist](docs/staging-acceptance.md) is complete.

The **prison** is the full jail boundary; the **quarry** is the smaller stone deposit
inside it. If you previously selected your whole jail as the quarry, first run
`/mp admin prison copyquarry`, then use `/mp admin quarry setpos1` and `setpos2` to
select only the mining area. Set a safe arrival anywhere inside the prison with
`/mp admin quarry setspawn`, put release and fallback outside the prison, and validate.
For a new jail, use `/mp admin prison setpos1` and `setpos2` to select the outer area.
Only the mining quarry is scanned for stone and limited to 50,000 blocks by default.
Both command branches need `mineplaypunishments.admin` and `mineplaypunishments.admin.quarry`.
The [setup guide](docs/community-service.md#quarry-setup) gives the complete sequence.

Version 1.2.1 automatically loads and retains the configured quarry and teleport
destination chunks at startup. It does not keep the entire prison loaded. Existing
worlds and generated terrain are required; layout and safety validation still run.
The optional `community-service.quarry.maximum-loaded-chunks` limit defaults to 256.

Staff with `mineplaypunishments.prison.build` (operators by default) can break,
place and collect normal block drops in the prison while not serving a sentence.
WorldGuard and other protection plugins still apply. Active prisoners remain bound
to quarry and tool restrictions even when they hold that permission.

Website sync is quiet during healthy polling and waits between retries. It logs
an initial failure, changes in failure category and a single recovery message.

## Public chat filter

Configure `chat-filter.banned-words` and `chat-filter.regex-patterns` to block public
Paper chat messages. Both lists start empty. Literal rules match whole words or
phrases without case sensitivity; regex rules use RE2/J on normalized text.
Blocked messages are cancelled and the sender sees the configurable
`chat-filter-blocked` message. The filter does not issue punishments, grant staff a
bypass, or inspect private-message commands.

Install the new JAR with a full restart. For an existing installation, add the optional
`chat-filter` section to `config.yml`; missing settings leave the rules empty. After
editing rules or the message template, use `/mp admin reload`. Invalid rule changes
leave the previous working filter active. See the [chat-filter guide](docs/chat-filter.md)
and the [ready-to-use UK adult-community preset](docs/chat-filter-uk-adult.md)
for examples, limits, Unicode handling and checks with your actual chat/relay plugins.

## Announcements and warning records

Local staff commands and GUI confirmations announce successful punishment issues,
revocations and Community Service changes in public chat. Add `-s` or `--silent`
to suppress the public notice, including `/punish <player> -s`. Silent actions
still save records and notify/enforce the affected player. The menu shows the
chosen announcement mode. Customize `punishment-announcement` in `messages.yml`;
older message files receive the built-in fallback without being overwritten.
Website-originated actions retain their existing behavior.

`/warnings [player]` (alias `/warns`) lists saved warnings, including expired,
revoked and acknowledged warnings. `/history [player]` includes every punishment
type. Both show ten entries per page; use `--page 2` for older records. Use
`/mineplaypunishments:warn` and `/mineplaypunishments:warnings` if another plugin
owns the short command. Records created by another plugin are not imported.

Version 1.2.0 also fixes unrelated players being disconnected when community-service
writes temporarily invalidate the moderation cache. Existing players wait for the
next valid enforcement pass; login and communication checks remain guarded. Commands
immediately enforce only their target. Disabled Community Service menu items now
show the specific permission, configuration, equipment or quarry readiness failure.

## Staff punishment menu

Authorized staff can run `/punish <online player|known offline name|uuid>` in-game
to choose Ban, Mute, Kick, Warn or Community Service. Ban and Mute offer Permanent, 1 hour, 4 hours,
8 hours, 1 day, 1 week or 1 month (30 days). A native Paper dialog collects the
required reason and requires an explicit confirmation before submitting the action.
Reasons are not collected through public chat.

Community Service offers 100, 250, 500, 1000, 2500 and 5000 blocks, plus a custom
whole-number amount. Change `community-service.gui-block-presets` to adjust the
presets; the first six distinct values within the configured minimum/maximum are
shown. Custom amounts obey the same bounds (10–10000 by default). A validated quarry
and `mineplaypunishments.service.issue` are required; the configured default prison
pickaxe is used. The [quarry guide](docs/community-service.md) gives the setup commands.

Opening the menu requires `mineplaypunishments.gui`; each action also requires its
existing moderation permission. Permissions and staff hierarchy are checked again
when submitting. Unknown offline names are rejected. Closing or cancelling the flow,
allowing the draft to expire, or reloading the plugin settings cancels the draft.

Existing commands remain available, including modifying/releasing Community Service
and revocations. `/service issue <player> <blocks> <reason>` and
`/service <player> <blocks> <reason>` both start a new sentence. `/service add` only
extends an existing sentence; a player with no active sentence needs an issue command.
In command durations, `1m` means **one minute**;
the menu's **1 month** option means **30 days**. See the
[command guide](docs/commands.md) for the full permission mapping and the
[real-client menu checklist](docs/staging-acceptance.md#punishment-menu-with-a-real-client)
for the required interaction checks. This description does not establish that those
client checks have been performed.

## Website connection

Implement the [OpenAPI contract](docs/api/openapi.yaml) and follow the
[ASP.NET/PostgreSQL integration notes](docs/website-integration.md). Set the per-server
credential in the **server process environment**, using the variable named by
`website.credential-env`. Never put it in source control. Credentials are read for
each request, but changing an OS environment variable normally requires restarting
the server process. HTTPS redirects are refused. Each request has a request ID and
the authenticated server ID; responses must echo the request ID and schema version.

SQLite remains authoritative for game enforcement and inventory recovery. Remote
requests are revision-checked commands, not database replicas. A committed action
is acknowledged only after the enforcement cache has refreshed. Kick delivery and
Community Service inventory restoration have their own terminal events; a remote
command acknowledgement is not proof that a kick or inventory restore finished.

When the site is offline or credentials are missing, bans, mutes, quarry custody,
history, the durable outbox and pending appeal provisioning continue locally.
Players get an explicit pending-provisioning message and their punishment reference
until the backend has registered a usable appeal token. Only the affected player,
authorized staff and the backend receive a provisioned link. Public history/outbox
events omit token URLs and inventory snapshots.

## Enforcement and failure policy

- Login waits up to five seconds for a serialized SQLite refresh. A failed/unverified
  store rejects login; a website outage does not. Startup storage/configuration failure
  leaves a login guard active and requires repair plus restart.
- Chat uses the in-memory cache with no database access. Temporary punishments use
  UTC expiry checks immediately; the durable expiry worker records transitions.
- Mutes block Paper's modern chat event and listed communication command labels,
  including namespaced forms. Configure **all aliases** from your installed plugins.
  Arbitrary plugin channels, proxy chat, Discord bridges or commands hidden behind
  aliases may need an explicit integration. Do not assume a cancelled Paper event
  retracts a message already forwarded by another plugin.
- Existing punishments remain enforced when issuing a type is disabled. Critical
  recovery protections, durable queues and WAL cannot be disabled. Unsupported
  escalation or alternate inventory modes are rejected during config validation.
- Database transactions run on one dedicated worker. Bukkit world/inventory work
  runs on the main thread; all player snapshots are immutable serialized data.
- Revocation never deletes history or snapshots. Inventory slots are replaced
  exactly during restoration, not merged; recovery restrictions remain until
  both live state and a fresh on-disk player-data receipt are verified.
- Multiple Paper servers must have separate SQLite files and server IDs. There is
  no cross-server inventory transfer protocol. Route a sentenced player back to
  their owning server and do not share the playerdata folder between live servers.

## Backups and recovery

Stop Paper cleanly before copying `plugins/MineplayPunishments/`, its SQLite database,
and all relevant world/playerdata files together. Retain the `-wal` and `-shm` files
if they exist; never copy only the `.db` from a running server. For online backups,
use SQLite's backup API/tool with a coordinated playerdata snapshot. Protect the
backup like player inventories and credentials: appeal links are private.

On a restored backup, start in an isolated environment first. Review snapshot
checksums and pending custody before allowing players to join. Never delete
`player_snapshots`, edit completed counters, or restore original items by hand while
automatic restoration is pending. That can create duplicate inventories.

For a player in recovery:

1. Keep the original database and playerdata backup.
2. Repair the unavailable world or safe release/fallback location.
3. Have the player online and run `/mp admin recover <uuid>` from authorized staff or console.
4. Check the release message, inventory/XP/effects, `CONFIRMED` snapshot state and
   `COMPLETED` sentence. A failed verification retains the snapshot and restrictions.

Emergency recovery is an audited early release. If a snapshot checksum fails,
restore a known-good coordinated backup or perform a supervised forensic recovery;
the plugin deliberately will not guess the original inventory.

`/mp admin reload` reloads supported settings; database path/server ID changes require
a restart. Do not use Bukkit `/reload` or hot-unload the plugin during inventory custody.
The SQLite schema is versioned under [db migrations](src/main/resources/db/).

## Local staging

`./gradlew runServer` downloads the Paper server JAR automatically; compilation only
downloads the API. No manually downloaded server JAR or moderation plugin is needed.
It uses the separate `run/` directory. Accept Minecraft's EULA before starting it.
Keep staging bound to `127.0.0.1`, with a separate port and no production API credential.
The plugin itself never creates, resets or regenerates a world.

See [verification evidence](docs/verification.md) for the actual checks performed.
Automated tests and console smoke checks do not prove compatibility with every
third-party plugin, a real website deployment, or all player interaction paths.
