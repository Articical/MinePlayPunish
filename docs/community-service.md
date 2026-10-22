# Community Service setup and recovery

Community Service uses two areas: the **prison** is the full area where sentenced players may move; the **quarry** is the smaller stone deposit they can mine. The quarry must fit completely inside the prison in the same world. Admission remains disabled until the areas and the arrival, release and fallback locations are configured and validated. This plugin does not generate worlds, reset terrain, or override protection plugins.

## Quarry setup

Both prison and quarry setup require `mineplaypunishments.admin` and
`mineplaypunishments.admin.quarry`; there is no separate prison setup permission. These default
to operators. Granting only the quarry node does not grant the parent admin permission.
With LuckPerms, an authorized
administrator can grant the two nodes explicitly:

```text
/lp user YOUR_STAFF_NAME permission set mineplaypunishments.admin true
/lp user YOUR_STAFF_NAME permission set mineplaypunishments.admin.quarry true
```

If the existing quarry selection covers your **whole jail**, preserve it as the prison
**before** selecting the smaller mining area:

```text
/mp admin prison copyquarry
/mp admin quarry setpos1
/mp admin quarry setpos2
/mp admin quarry setspawn
/mp admin quarry setrelease
/mp admin quarry setfallback
/mp admin quarry validate
/mp admin status
```

`copyquarry` copies the currently saved quarry world and two corners to the prison
configuration. It does not fill blocks, change the mining selection, or add extra
height. Check that the copied cuboid includes the jail's walkways and arrival location.
Do not run it after shrinking the quarry unless you intend to replace the prison with
that smaller selection.

For a new prison, or to replace the outer boundary, look at its two opposite corner
blocks and run these instead of `copyquarry`, then continue with the quarry commands:

```text
/mp admin prison setpos1
/mp admin prison setpos2
```

Move or aim before each position command. The commands take no coordinate arguments.
Both `prison setpos1|setpos2` and `quarry setpos1|setpos2` save the block you are looking
at within six blocks. The arrival, release and fallback setters save where you are
standing, including your view direction. Console can run `prison copyquarry`, either
`validate` command, and `status`; position setters require an in-game player.

1. Select a prison cuboid containing the entire jail and the planned quarry in a loaded, existing world. Include the vertical space players will use. The prison may contain buildings, walls and other materials; it is not scanned for stone and is not subject to the quarry's 50,000-block default volume limit.
2. Prepare a compact stone deposit inside the prison. Look at opposite corner blocks and run `/mp admin quarry setpos1` and `/mp admin quarry setpos2`. These corners define only the mineable cuboid. Corners are inclusive, and both areas must share a world. The quarry may contain only configured ordinary stone materials and air; ores, chests, mechanisms and other infrastructure fail validation. Its default maximum volume remains 50,000 blocks.
3. Stand at a safe arrival location **anywhere inside the prison**, such as a walkway outside the mining deposit, and run `/mp admin quarry setspawn`. Keep its floor outside the mineable blocks so mining cannot remove it.
4. Stand at a safe location **outside the full prison**, not merely outside the quarry, and run `/mp admin quarry setrelease`.
5. Set another safe location outside the prison with `/mp admin quarry setfallback`. This may be in another existing world and permits recovery if the quarry becomes unavailable.
6. Run `/mp admin quarry validate` or `/mp admin prison validate`; both validate the complete setup. The plugin loads the already-generated quarry and destination chunks and retains them with plugin chunk tickets. The deposit must contain approved stone, and the three player locations must have solid floors and clear, nonhazardous headroom. Changing any selection or location disables admission until validation succeeds again.
7. Inspect the area yourself and test the server's actual protection and teleport plugins. The plugin cannot know whether a selected stone wall is infrastructure. Protection-plugin cancellations remain authoritative. Do not grant a protection bypass merely to make quarry mining work.

Tab completion follows both paths. `/mp admin ` offers `prison` and `quarry` with the
required permissions. `/mp admin prison ` offers `setpos1`, `setpos2`, `copyquarry` and
`validate`; `/mp admin quarry ` offers `setpos1`, `setpos2`, `setspawn`, `setrelease`,
`setfallback` and `validate`. Suggestions filter the text already typed: `setp` offers
the two corner setters. Console receives `copyquarry` and `validate` for prison and
only `validate` for quarry.

If another plugin owns `/mp`, use `/mineplaypunishments:mp admin prison copyquarry` and
the same `mineplaypunishments:mp` prefix for every other setup command. The namespaced
form also supports tab completion. If the expected branch is missing, check both
permission nodes and try this namespaced form before changing quarry settings.

Each setter and `copyquarry` saves to `config.yml`. Successful validation enables
admission and `/mp admin status` reports readiness. You do not need to reload between
setters. A rejected operation does not save a new selection. Both quarry corners and
all three player locations are required. Explicit prison bounds use
`community-service.prison.world`, `pos1` and `pos2`; configuring just one corner is an
error. To move the setup to a different world, clear the old corner selections in the
configuration before selecting both areas there; mixed-world corners are rejected.

Old configurations without any prison fields keep their existing confinement boundary:
the quarry footprint from its bottom through four blocks above its top. They do not
automatically gain a larger jail. An explicit prison uses exactly its selected cuboid,
with no extra four-block height allowance. Leave all prison fields unset to retain
legacy behavior, or select/copy both prison corners to separate the two areas.

Enclose the prison physically so players can navigate it normally. Prisoner movement
outside it is blocked, and only quarry blocks can earn mining credit. All teleports
other than an exact, currently authorised admission, rescue or release destination
are cancelled. The full prison boundary is protected against ordinary block changes;
prisoners' permitted quarry mining is the exception.

While Community Service is enabled, complete saved prison/quarry bounds protect the
area as soon as they are saved, even if mining validation fails or admission is not
ready. Non-prisoners need `mineplaypunishments.prison.build` to break or place blocks
there; this permission defaults to operators. A player serving Community Service
remains restricted even with that permission or operator status. Other protection
plugins can still deny building. A release or fallback inside protected bounds is
refused even when the mining layout is unavailable.

`community-service.maximum-prisoners` limits simultaneous online admissions. Additional assigned players remain restricted while waiting. Offline sentences do not occupy an online slot. The configured mining and destination chunks stay ticketed while Community Service is enabled and valid, including when no players are online.

## Chunk loading on startup and validation

The quarry world and any separate destination world must already exist and be loaded
by Paper or your world manager. MineplayPunishments does not create or load worlds.
At startup, explicit validation and configuration reload, it loads only the existing
chunks intersecting the mining cuboid, plus the arrival, release and fallback chunks.
It adds plugin chunk tickets to keep those chunks available without nearby players.
It does not pin the full prison cuboid or set persistent force-load flags.

All required chunks must already have been generated. Visit or pre-generate the
configured locations before enabling the quarry; the plugin refuses missing terrain
instead of generating it. Terrain and destination safety checks still run after
loading. Chunk tickets do not make an unsafe floor, headroom or stone selection safe.

`community-service.quarry.maximum-loaded-chunks` bounds the combined, deduplicated
mining and destination footprint. The default is **256**, with a permitted range of
**1–1024**. This is separate from `maximum-volume`, so a long, thin quarry cannot
load an unbounded strip of chunks. Keep the limit close to the real footprint; plugin
tickets consume normal server memory and chunk processing resources.

A failed validation releases newly acquired tickets and leaves admission unavailable.
Changing configuration releases the previous tickets before preparing the new layout.
Disabling Community Service or stopping the plugin releases its tickets. Tickets
owned by other plugins and pre-existing persistent force-load flags are not changed.
A missing or unloaded world requires operator/world-manager repair; chunk tickets do
not bypass that requirement or custody recovery checks.

## WorldGuard around the full jail

The existing plugin can work inside a WorldGuard-protected jail. This needs region
configuration, not a code or JAR change: WorldGuard allows breaking in the mining
deposit, then MineplayPunishments enforces the active sentence, allowed stone,
survival mode and sentence-tagged pickaxe. WorldGuard documents `block-break allow`
as the specific way to permit mining without permitting placement.
See [WorldGuard's mining scenario](https://worldguard.enginehub.org/en/latest/regions/common-scenarios/#how-do-i-allow-mining-but-prevent-block-placing).

Use two regions in the same world. The following commands create **new example
regions**, with jail priority 0 and quarry priority 10. If the jail already exists,
use its actual name, skip `/rg define mp_jail`, and **keep its current priority**
instead of running `/rg setpriority mp_jail 0`. Choose a child priority above any
overlapping block-breaking denial. For a new jail, first select the **whole jail**
with WorldEdit, matching the plugin's prison boundary:

```text
/rg define mp_jail
/rg setpriority mp_jail 0
/rg flag mp_jail -g all block-break deny
/rg flag mp_jail -g all block-place deny
```

Then make a new WorldEdit selection of **only the mineable stone deposit**, matching
the plugin's quarry corners exactly, including height:

```text
/rg define mp_quarry
/rg setparent mp_quarry mp_jail
/rg setpriority mp_quarry 10
/rg flag mp_quarry -g all block-break allow
```

The child inherits the jail's placement denial while its own `block-break` flag
permits mining. The plugin's `/mp admin ... setpos` commands and WorldEdit selections
are separate; changing one does not update the other. Inspect both WorldGuard regions
with `/rg info mp_jail` and `/rg info mp_quarry`, and inspect their flags before testing.
Parent flags apply when the child does not define that flag; a higher-priority parent
can override its child. See [WorldGuard priority and inheritance](https://worldguard.enginehub.org/en/latest/regions/priorities/).

Priorities **0 and 10 are examples**. The quarry's priority must be above every
overlapping region that denies block breaking. At equal priority, a conflicting
`deny` wins. Also inspect existing region groups: the example uses `-g all` so the
mining exception is not limited to WorldGuard members. An explicit `block-break`
setting can override `build` denial; keep the override specific instead of enabling
`build` or `passthrough`. See [WorldGuard flag resolution](https://worldguard.enginehub.org/en/latest/regions/flags/#conflicting-flags)
and [protection flags](https://worldguard.enginehub.org/en/latest/regions/flags/#protection-related).

MineplayPunishments must remain enabled with the matching prison/quarry configuration
for **prisoner-only mining**. WorldGuard's child-region allowance alone permits
everyone to break there; this plugin still cancels visitors without
`mineplaypunishments.prison.build`, prevents prisoners from breaking the jail walls,
and suppresses drops and XP from qualifying mining. The building permission defaults
to operators and applies only when they are not serving Community Service; it does
not override WorldGuard. Keep prisoner accounts outside WorldGuard membership lists and without
world-wide bypass permissions. A WorldGuard or other protection-plugin cancellation
is never undone and earns no work credit, including a cancellation later in the event.

Avoid adding WorldGuard `entry`/`exit` denials as part of this setup: they can also
block admission or release teleports. MineplayPunishments supplies confinement.
Run the [two-player staging checks](staging-acceptance.md#community-service-with-two-real-players)
with the actual protection stack before using the jail for normal players.

## Issuing and extending sentences

After validation, create a disposable test sentence with either equivalent command:

```text
/service issue <player> 10 "Quarry test"
/service <player> 10 "Quarry test"
```

Use one command, with `mineplaypunishments.service.issue` and sufficient staff rank.
Use a count within your configured limits if the default minimum of 10 was changed.
Both forms also accept `--tool wooden` or `--tool stone` when that pickaxe is enabled.
Check `/service progress <player>` with `mineplaypunishments.service.progress.others`
and perform the staging checks below before sentencing normal players.

`/service add <player> <blocks> <reason>` **adds work to an existing active sentence**
and needs `mineplaypunishments.service.modify`. It does not create a first sentence.
If it reports that there is no active sentence, use `/service issue` or the original
`/service <player> <blocks> <reason>` form. `/service release <player> [reason]` ends an
existing sentence through the normal restoration path and needs `service.release`.

## Equipment and inventory persistence

Admission follows `ASSIGNED → SNAPSHOTTED → ACTIVE`. The original inventory, armour, offhand, selected slot, cursor, experience, health, hunger, saturation, exhaustion, potion effects, game mode, flight state and location are encoded into a versioned snapshot. SQLite stores its SHA-256 checksum and commits it with FULL synchronous WAL durability. The manager reads the committed record back and verifies its checksum before clearing equipment or teleporting.

Admission waits while a player has an external inventory screen, cursor item, or crafting input open. Vanilla's saved player file does not reliably retain those transient inventories. Nothing is cleared during this wait. If the player cannot safely close the screen, staff should release the unstarted assignment, let them empty the cursor/crafting slots normally, then reissue the sentence. An offline assignment admits normally on the next login.

The prison tool is a wooden or stone pickaxe tagged with its sentence UUID in PersistentDataContainer. Display-name imitation does not authorise a tool. Prison tools are unbreakable and cannot be dropped, swapped, moved through inventories, picked up, or transferred through hoppers. Snapshot contents are never sent to the website.

Reconnects reload and verify the same snapshot and reissue prison equipment. They never capture prison inventory as a replacement for the original. Maximum prisoner capacity can delay readmission without discarding state.

## Mining and regeneration

A credit requires an active sentence, survival mode, an approved block within the quarry, the designated tagged tool, and an unreserved position. The valid break suppresses drops and experience. Drop events in the quarry are also cancelled.

The manager observes the break at MONITOR and finalises on the next server tick. It rechecks the event's final cancellation flag, actual air at the position, the player's current session, and sentence state. A later protection-handler cancellation, an unchanged block, a disconnected player, or a completed/released sentence receives no credit. A position is reserved while a credit is pending. SQLite's unique position constraint prevents two prisoners from receiving credit for the same regeneration cycle.

Progress, the pending regeneration position and its audited outbox event commit together. The website transport batches events; mining never performs an HTTP request on the server thread. The queue records world UUID, block coordinates, replacement material and due time. It survives a restart. Unloaded worlds/chunks defer processing. A block is replaced only if it is still air and still belongs to the current quarry; any different block is left untouched. Positions outside a reconfigured quarry remain pending until reviewed and that quarry is deliberately restored. Work is bounded by `regeneration.max-blocks-per-tick`; queue inspection is bounded separately to four times that limit.

Visitors without `mineplaypunishments.prison.build` cannot break or place blocks in the protected prison area. Explosions, pistons, fluids and entity block changes are also blocked there. Authorized builders still follow other protection plugins, and prisoners remain restricted regardless of their building permission. Administrative commands and arbitrary third-party direct world edits cannot be intercepted universally. Stop admissions and clear prisoners before editing a prison or quarry using WorldEdit or another administrative tool, and validate it again afterwards. An external plugin that deliberately changes drops after all standard handlers also requires integration testing.

Minecraft world saves and SQLite cannot form one atomic transaction. A crash between the actual block break and its deferred local credit can leave an uncredited air block; a crash around regeneration and a world save can leave a missing block. This fails toward missing work/terrain rather than duplicate credit or tradable drops. Inspect and repair quarry terrain after a hard crash. Do not interpret database tests as a proof that unrelated plugins cannot override world state.

## Completion and administrative recovery

Completion follows `ACTIVE → WORK_COMPLETE → RESTORING → COMPLETED`. Staff release and appeal acceptance use the same restoration path. A release before first admission needs no snapshot because the original player state was untouched.

Before restoring, the manager commits restoration intent. It overwrites exact saved slots rather than adding saved items to the current inventory, removes prison equipment, restores player state, and teleports to the configured release or fallback. It then verifies live equipment, effects, game mode, experience, health and food against the snapshot and checks the actual destination.

A unique restoration receipt is written to the player's PersistentDataContainer before `saveData()`. A bounded compressed-NBT reader then checks that fresh receipt under the root `BukkitValues` compound in `<server level directory>/players/data/<UUID>.dat`, located through Paper's `Server.getLevelDirectory()`. An old, absent, corrupt or unreadable saved file cannot confirm restoration. Only after this read-back succeeds does SQLite record `CONFIRMED` and complete the sentence. The snapshot remains retained. This protocol requires Paper's normal player-data storage; servers replacing that storage need an explicit compatible receipt verifier and will otherwise remain safely in recovery.

The receipt proves that the newly saved player file was readable. It is not a promise against storage-controller failure, external rollback of player files, or a later independent inventory plugin rewriting those files. Back up Minecraft player files and the SQLite database together while stopped for a consistent disaster-recovery point.

If any check fails, state becomes `RECOVERY`. The player remains restricted; the snapshot is retained. Restore a safe release/fallback destination, inspect the console and backups, then run `/mp admin recover <player>` with the player online. This permission-protected command audits an emergency release and repeats restoration from the original snapshot. It never deletes a snapshot or grants the inventory by appending items. Repeating it while a transition is in progress is refused.

Do not manually set the sentence to COMPLETED or delete a recovery record to bypass a failed restore. Do not allow inventory plugins to transfer or modify a recovering player's contents. If the saved snapshot is damaged, restore from a consistent backup and investigate the storage failure before releasing custody.

### Still restricted after release on an older build

The earlier receipt check used `World.getWorldFolder()` and the old `playerdata`
subdirectory. On Paper 26.3 the world folder can point to a dimension directory, and
the player-data location is now `players/data` under the server level directory.
Looking in the wrong place kept the player in `RECOVERY`. The updated build reads
from `Server.getLevelDirectory()/players/data`, reports the underlying failure in
the log, and clears the in-progress recovery flag so staff can retry.

Install the updated JAR and fully restart Paper. Keep the retained snapshot, ensure
the configured release or fallback is safe and outside the full prison, then have
the affected player online and run `/mp admin recover Mogamary` (substitute another
affected player's name when needed). Check the release message and restored state.
This procedure does not establish that a particular player's recovery has succeeded;
a remaining file or destination error keeps custody active and must be repaired first.

## Verification boundary

Automated tests exercise the SQLite state machine, retained snapshots, checksum failure, offline early release, concurrent mining positions, rollback atomicity, completion thresholds, restart recovery, NBT receipts, and restriction listener decisions. They are not a live Paper/WorldGuard acceptance test.

Before production, exercise admission, a full sentence, an early release, disconnect/reconnect, two prisoners mining the same position, late event cancellation, void/suffocation rescue, every installed teleport route, every inventory menu, a restart with pending regeneration, and a forced restoration-file write failure. Confirm no drops or XP appear and compare inventory/armour/offhand/XP/effects before and after. Test the receipt layout on the exact Paper build. Retain staging logs and backup copies for the recovery cases.
