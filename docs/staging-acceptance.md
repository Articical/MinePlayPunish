# Staging acceptance checklist

Use disposable accounts, an isolated server, a separate server ID, and a test backend.
Back up the staging database and playerdata before crash/recovery tests. Record actual
results and versions in `docs/verification.md`; unchecked items have not been verified.

## Base moderation

- [ ] Java 25 / Paper 26.3 starts, plugin enables and SQLite reports WAL/FULL/foreign keys.
- [ ] Console and authorized staff issue permanent/temporary bans and mutes. Confirm
  target disconnect/login denial, stable reference, expiry and appeal fallback/link.
- [ ] Stop the website; restart Paper; bans/mutes remain enforced. A failed SQLite
  refresh rejects login and does not silently unban anyone.
- [ ] Revoke a punishment and confirm immediate cache change; preserve history.
- [ ] Online warning is shown; offline warning arrives on next login. Delivery and
  explicit acknowledgement have different timestamps/states.
- [ ] Kick an online account, an offline UUID and a player who disconnects mid-claim.
  Confirm EXECUTED/FAILED/EXPIRED, no stale kick after reconnect, and uncertain claims
  fail rather than replay after restart.
- [ ] Ordinary players, lower-ranked staff and console exercise permission paths.
  Test online/offline LuckPerms ranks, explicit overrides and command namespaces.
- [ ] Test Paper public chat, Essentials `/msg`, `/r`, `/mail`, namespaced aliases,
  Chatter/Discord bridges and every installed messaging plugin. Document any channel
  that requires a separate integration before deployment.

## Public chat filter

Use the harmless [chat-filter example](chat-filter.md#setup), disposable accounts
and the actual chat renderer/relay plugins. These checks require real chat delivery;
unit tests and successful compilation do not complete them.

- [ ] Install the new JAR with a full restart. With the optional section absent,
  or enabled with both lists empty, ordinary chat still reaches a second client.
  Add rules and successfully run `/mp admin reload`; no restart is needed for rule edits.
- [ ] `sampleword`, mixed-case variants and punctuation around that word are blocked.
  `samplewords` and `my_sampleword` remain allowed. Test the literal phrase with its
  exact configured spaces, extra spaces and Unicode letters around the match.
- [ ] Test full-width letters and inserted zero-width format characters. Check
  expected normalized matches without assuming all confusable alphabets or leetspeak
  are detected.
- [ ] Test both a regex match inside a longer message and an anchored whole-message
  pattern. Try valid counted repetitions, unsupported lookarounds/backreferences,
  blank rules, oversized rules and an expression exceeding the compilation budget.
  Invalid reloads must report failure and keep the previous working rules active.
- [ ] Confirm a blocked message is absent from the other client's public chat and
  only the sender receives the generic configurable response. Check the message
  template after reload. No warning, mute, kick, sentence or punishment record is created.
- [ ] An operator and ordinary player receive the same filtering. Existing mute
  enforcement still blocks chat that would otherwise pass the filter. Disabling
  the filter and successfully reloading restores otherwise permitted public chat
  without clearing an existing mute.
- [ ] `/msg` and other private commands are not inspected by this filter; distinguish
  any separate mute or third-party restrictions when checking them.
- [ ] Verify both allowed and blocked messages through the installed chat renderer,
  proxy and Discord/other relays. Record any relay that forwards before cancellation
  or ignores it, and configure/integrate that route before claiming it is filtered.
- [ ] Where the staging client or test helper supports it, exercise the 4,096-character
  input limit with rules active and confirm bounded behavior during concurrent chat
  and admin reloads. Check logs for asynchronous Bukkit API errors or reload failures.

## Punishment menu with a real client

These are manual acceptance checks for the actual Paper 26.3 server and compatible
Minecraft client. Automated tests or successful compilation do not complete them.
Use disposable moderator and target accounts and record the client/server versions.

- [ ] Run `/punish` for an online player, a verified offline name and a UUID.
  Confirm that the displayed target is correct and an unknown offline name is rejected.
  Console execution gives a useful player-only response and existing console commands
  still work.
- [ ] Verify that an ordinary player cannot open the menu. A staff account with only
  `mineplaypunishments.gui` cannot execute an action; separately test the `ban`,
  `tempban`, `mute`, `tempmute`, `kick` and `warn` nodes. Removing the GUI or action
  permission after opening the menu must prevent submission.
- [ ] Change the actor's or target's staff rank, or the target's protected permission,
  while a draft is open. Confirm hierarchy is rechecked on submission for online and
  offline targets and an unauthorized action creates no punishment or audit success.
- [ ] For both Ban and Mute, select Permanent, 1 hour, 4 hours, 8 hours, 1 day, 1 week
  and 1 month. Check the confirmation details and resulting UTC expiry. Permanent has
  no expiry; 1 month is 30 days. Separately run a temporary command with `1m` and verify
  that it expires after one minute.
- [ ] Kick and Warn open the native reason-and-confirmation dialog. Ban and Mute open
  it after a duration is chosen. Verify the target, action and duration remain correct
  through each transition; nothing is submitted until explicit confirmation.
- [ ] Empty or whitespace-only reasons cannot submit. Exercise maximum length,
  overlong input, quotes, Unicode, command-like text and literal MiniMessage tags such
  as `<red>`. Verify reasons remain readable literal text in previews, notifications,
  history and the stored record; markup is not interpreted and commands do not execute.
- [ ] Keep a second player online while entering a reason. Confirm it is never sent
  to public chat or captured from unrelated chat messages. Check any installed chat
  bridge as well.
- [ ] Try dragging, shift-clicking, number-key/hotbar swaps, double-click collection,
  dropping, offhand swaps and clicks in the player's own inventory while a menu is open.
  Menu icons cannot be taken, moved or duplicated; ordinary inventory items are not
  lost; unintended clicks cannot submit moderation.
- [ ] Close the menu, cancel the dialog, let the draft expire, and run `/mp admin reload`
  while a draft is open. Verify each cancels it without a punishment; stale menu/dialog
  interactions cannot revive it. Reopening `/punish` begins a fresh draft.
- [ ] Double-click confirmation, repeat clicks quickly, and attempt an old confirmation
  after a fresh menu is opened. Verify exactly one intended punishment, audit creation
  and outbox event, with no duplicate warning or kick delivery.
- [ ] Disconnect the moderator during the flow and the target just before confirming.
  Verify safe cancellation for the moderator, correct offline handling for persistent
  punishments, and an honest failed/offline delivery result for a kick.
- [ ] Confirm the existing text commands still operate with their original permissions
  and duration syntax. Revocation and sentence modification/release remain command workflows.
- [ ] With `gui` and `service.issue`, select each Community Service preset and a custom
  amount. Confirm target, amount, reason, configured pickaxe and the actual stored
  sentence match. A second assignment must not overwrite an existing sentence.
- [ ] Custom input rejects blank, fractional, negative, oversized, out-of-range and
  nonnumeric values without creating a sentence. Exercise both configured bounds.
  Changing a fixed preset's submitted amount cannot change the server-selected count.
- [ ] Change `gui-block-presets`, reload and check the first six distinct valid values.
  Remove all valid presets and confirm Custom still works within configured bounds.
- [ ] Unvalidate/reconfigure the quarry or revoke `service.issue` while a draft is open;
  confirmation must fail without inventory custody. Repeat with a protected target.
- [ ] Tab-complete `/mp admin `, `/mp admin quarry ` and partial quarry operation names.
  Repeat using `/mineplaypunishments:mp`; users without admin/quarry permissions must
  not receive those suggestions. Check the existing protection/plugin stack on the client.

## Community Service with two real players

- [ ] Set and validate a full prison boundary, a smaller existing-world quarry,
  arrival inside the prison, and release/fallback outside it. Keep infrastructure
  and other regenerators outside the mineable deposit. Intentional overlap with the
  [WorldGuard jail and quarry regions](community-service.md#worldguard-around-the-full-jail)
  is supported; confirm matching selections, parentage, priorities and flag groups.
- [ ] Snapshot includes all inventory slots, armour, offhand, XP, health, hunger,
  effects, game mode and original location. Nonempty cursor/crafting/open GUI safely
  defers admission without losing items; early-release and clear the GUI to retry.
- [ ] Only authorized PDC-tagged tools count; test display-name forgery, stone vs
  wood requirements, survival vs creative, wrong materials and outside breaks.
- [ ] With WorldGuard denying breaks/placement in the outer jail and the higher-priority
  child quarry allowing breaks, a non-operator prisoner with no WorldGuard membership
  or bypass can mine approved stone with the tagged tool and receive one credit.
  The prisoner cannot break jail walls or place blocks anywhere in the prison.
- [ ] In that same WorldGuard-allowed quarry, an ordinary visitor without
  `mineplaypunishments.prison.build` still cannot break or place blocks. An
  authorised builder can break/place and receive normal drops when WorldGuard
  permits it; the same permission cannot bypass an active sentence. Confirm that the
  plugin remains enabled; WorldGuard's mining allowance alone is not prisoner-specific.
- [ ] Temporarily deny a quarry break through WorldGuard and verify that the block
  remains and no work credit is granted. Repeat with another protection handler and
  a later MONITOR cancellation. Restore the intended flags and confirm mining works
  again; the plugin must never undo another handler's cancellation.
- [ ] No drops, XP, economy income or pickup. Each coordinate counts once per cycle;
  two simultaneous prisoners cannot both claim one block. Repeated regeneration works.
- [ ] Regeneration obeys per-tick bound, persists across restart, waits for unloaded
  chunks and does not overwrite modified/non-air blocks.
- [ ] Placement, explosions, pistons, fluids and entities cannot create qualifying work.
  Exercise installed WorldEdit/FAWE integrations and keep external quarry edits disabled.
- [ ] Try all escape paths: home/spawn/tpa/warp/back, plugin teleports, pearls,
  chorus fruit, portals, spectator/flight, mounts, inventories, drops and swapping.
- [ ] Test player/mob/projectile/environmental damage, suffocation, void and forced
  death/respawn; sentence and original inventory remain intact.
- [ ] Disconnect during snapshot, admission, mining, WORK_COMPLETE and RESTORING.
  Restart/kill at those boundaries and verify exact inventory restoration, no duplication.
- [ ] Fill storage or make the server level's `players/data` directory unwritable. Restoration remains
  restricted with snapshot retained until live-state and on-disk receipt verification pass.
- [ ] Complete work, staff early-release, add work explicitly, reduce work remotely,
  and recover from missing world/corrupt snapshot. Confirm audit and completion events.

## HTTPS/backend contract

- [ ] Implement the OpenAPI contract on an authenticated staging backend. Test TLS,
  timeout, 401/403, redirects, credential rotation, invalid schema/request ID/server ID.
- [ ] Deliver actions twice, reordered, after crash, with changed payload under the
  same action ID, and with stale expected revisions. No duplicate moderation/work.
- [ ] Crash between local commit, cache refresh, acknowledgement and cursor update.
  Restart reconciliation must recover revocations and missed commands.
- [ ] Duplicate outgoing events have one backend effect; outage never discards outbox.
- [ ] Issue offline remote bans/service/warnings and verify locally before website
  marks applied. Kick and inventory delivery remain separately pending until confirmed.
- [ ] Provision a backend-owned opaque 256-bit token; reconnect shows the same URL.
  Rotate/revoke it and confirm no public history/event/log exposes the secret.
- [ ] Accept an appeal, reject an appeal, reduce duration and reduce required work.
  Only revisioned accepted commands change local enforcement; rejection has no action.
- [ ] Do not sign off production readiness until this checklist is complete with the
  actual proxy, protection, economy and chat plugin stack.

## Cold-start prison chunks and quiet sync (1.2.1)

- [ ] Restart with no player near a configured prison. Quarry validation succeeds
  and the service menu remains available without visiting the area first.
- [ ] Quarry plus arrival/release/fallback chunks have plugin tickets; the rest of
  a large prison has none. Leave the area idle and verify it remains ready.
- [ ] Revalidate/reload, edit locations, fail unsafe validation and disable the
  plugin; confirm obsolete tickets are removed and no persistent force-load flags
  are introduced. Other plugins' tickets remain intact.
- [ ] With healthy website sync, ordinary polls produce no repeated console lines.
  An outage logs once per failure category; recovery logs once. Confirm records
  still synchronize and retries preserve the durable queue.
