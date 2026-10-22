# Commands and permissions

Command inputs are parsed by picocli with Brigadier quote handling. Multiword reasons
may be quoted or unquoted. Options are explicit; `--` ends option parsing when a
reason must start with an option-like word. Argument-file and environment-variable
expansion are disabled. Invalid, empty, overlong and control-character reasons are rejected.
Use `30m`, `2h`, `7d`, `30d`, `1w` or `permanent`; zero, negative and excessive durations fail.
The `m` suffix means minutes: `1m` is one minute, not one month. The punishment
menu's **1 month** preset is a fixed duration of 30 days.

Targets accept online exact usernames, UUIDs, or unambiguous usernames verified by
an earlier login. Unknown names are rejected. Remote requests identify players by UUID;
a website-supplied display name cannot establish a verified username-to-UUID mapping.
Use `mineplaypunishments:<command>` when another plugin owns a conflicting short command.

| Command | Permission suffix under `mineplaypunishments.` |
| --- | --- |
| `/punish <player>` | `gui`, plus the permission for the chosen action below; player-only |
| `/ban <player> <reason>` | `ban` |
| `/tempban <player> <duration> <reason>` | `tempban` |
| `/unban <player> [reason]` | `unban` |
| `/mute <player> <reason>` | `mute` |
| `/tempmute <player> <duration> <reason>` | `tempmute` |
| `/unmute <player> [reason]` | `unmute` |
| `/warn <player> <reason>` | `warn` |
| `/kick <player> <reason>` | `kick` |
| `/service <player> <blocks> <reason> [--tool wooden\|stone]` | `service.issue` |
| `/service issue <player> <blocks> <reason> [--tool wooden\|stone]` | `service.issue`; explicit alias for a new sentence |
| `/service add <player> <blocks> <reason>` | `service.modify` |
| `/service release <player> [reason]` | `service.release` |
| `/service progress [player]` | Self: available; others: `service.progress.others` |
| `/history [player] [--page number]` | `history`; other targets also need `history.others` |
| `/warnings [player] [--page number]` (alias `/warns`) | `warnings` or `history`; other targets also need `warnings.others` or `history.others` |
| `/checkban <player>` | `checkban` |
| `/checkmute <player>` | `checkmute` |
| `/mp acknowledge <punishment-uuid>` | Only the affected player, no staff authority |
| `/mp appeal <punishment-uuid> [--rotate]` | `appeal` |
| `/mp admin status` | `admin` |
| `/mp admin sync` | `admin` and `admin.sync` |
| `/mp admin reload` | `admin` and `admin.reload` |
| `/mp admin recover <player>` | `admin` and `admin.recover` |
| `/mp admin quarry <operation>` | `admin` and `admin.quarry` |
| `/mp admin prison <operation>` | `admin` and `admin.quarry` |

All administrative permissions default to operator-only. `bypass.staff`, `protected`
and rank nodes default to false. Progress for self and acknowledging one's own warning
do not grant any ability to change a punishment. Console may run moderation commands;
prison/quarry position setters require an in-game player and recovery requires the
target online. `prison copyquarry` and either validation command also work from console.

## Public announcements and silent commands

Successful local punishment issues, bans/mutes being revoked, and Community Service
issues/extensions/releases are announced in chat. Add `-s` or `--silent` anywhere
among a mutation command's arguments to suppress the public announcement:

```text
/tempban Player 1d Griefing -s
/warn Player Repeated harassment -s
/service issue Player 250 Griefing -s
/punish Player -s
```

The reason excludes the flag. Use `--` before a reason that literally contains
`-s`, for example `/warn Player -- Do not use -s in public chat`. Silent mode does
not suppress recordkeeping, target feedback, enforcement or website synchronization.
The menu retains its mode throughout confirmation and shows it on each inventory
screen. Read-only/admin commands do not accept a silent flag. Public wording comes
from `punishment-announcement` in `messages.yml`; its placeholders are escaped and
never include appeal links. A kick notice says it was issued; actual delivery is
tracked separately. Remote website actions retain their existing behavior.

## Warning records and history

`/warn <player> <reason>` issues a warning. `/warnings [player]` and its `/warns`
alias list only warning records, newest first, regardless of acknowledgement,
expiry or revocation. `/history [player]` lists all punishment types, including
`WARNING`. Both use ten-record pages and print a next-page command when needed;
`/warnings Player --page 2` and `/history Player --page 2` show older records.
Delivery and acknowledgement do not remove warning records.

If Essentials or another plugin handles `/warn` or `/history`, explicitly use
`/mineplaypunishments:warn`, `/mineplaypunishments:warnings` and
`/mineplaypunishments:history`. This plugin cannot show a warning saved only by a
different plugin. The original history/history.others permissions also authorize
the corresponding warning views, so existing staff groups keep access.

## Prison, quarry and Community Service commands

`mineplaypunishments.prison.build` lets non-prisoners break/place blocks, collect
normal block drops and use buckets in the protected prison area. It defaults to
operators. Grant it to a staff group or individual through your permissions plugin;
for example, `/lp user Player permission set mineplaypunishments.prison.build true`.
It does not override another plugin's cancellation or a WorldGuard denial, and it
does not exempt sentenced/recovery players from custody restrictions. Configure
WorldGuard staff access separately when using the jail/child-quarry region recipe.

The prison is the full confinement area; the quarry is its smaller mineable stone
deposit. Use `/mp admin prison setpos1` and `setpos2` to select the jail boundary.
If your existing quarry selection already covers the whole jail, run
`/mp admin prison copyquarry` **before** reselecting the smaller quarry with
`/mp admin quarry setpos1` and `setpos2`. The copy preserves the saved corners and
world; it does not fill or change terrain. Both areas must be in the same world,
with the quarry entirely inside the prison. Only the quarry has the stone-only
validation scan and default 50,000-block volume cap.

`/mp admin quarry setspawn` records a safe arrival location anywhere in the prison.
`setrelease` and `setfallback` must be outside the full prison. Run either
`/mp admin prison validate` or `/mp admin quarry validate` after finishing all
positions; both check the complete setup. See the [setup sequence](community-service.md#quarry-setup)
for selecting corners, migration from older configurations, permissions and tab completion.

To start a sentence, use `/service issue <player> <blocks> <reason>` or the original
`/service <player> <blocks> <reason>` form. `/service add` only adds blocks to an
existing active sentence; it never creates one. If there is no active sentence,
issue a new one with `service.issue` permission. Extending work requires
`service.modify` instead. Neither command bypasses the staff hierarchy or configured
block-count limits.

## Punishment menu

Run `/punish <online player|known offline name|uuid>` to open a menu for one target.
Offline names must be verified by an earlier login; supplying a UUID does not bypass
the normal staff hierarchy or permission checks. The menu is for an in-game player;
console retains the existing moderation commands. Add `-s` to the opening command
for a silent confirmation. Disabled Community Service items display the specific
failed permission, enabled setting, prison equipment or quarry readiness check.

The `mineplaypunishments.gui` permission only grants access to the menu. It does not
grant punishment authority. The selected action requires its usual node:

| Menu selection | Additional permission |
| --- | --- |
| Ban → Permanent | `mineplaypunishments.ban` |
| Ban → timed preset | `mineplaypunishments.tempban` |
| Mute → Permanent | `mineplaypunishments.mute` |
| Mute → timed preset | `mineplaypunishments.tempmute` |
| Kick | `mineplaypunishments.kick` |
| Warn | `mineplaypunishments.warn` |
| Community Service → preset or custom blocks | `mineplaypunishments.service.issue` |

Ban and Mute first offer these choices:

| Preset | Duration |
| --- | --- |
| Permanent | No expiration |
| 1 hour | 1 hour |
| 4 hours | 4 hours |
| 8 hours | 8 hours |
| 1 day | 24 hours |
| 1 week | 7 days |
| 1 month | 30 days, not a calendar-month calculation |

After choosing a duration, enter a reason in the native Paper dialog and explicitly
confirm the action. Kick and Warn go directly to the reason-and-confirmation dialog.
A reason is required for every action. The same length and content validation used
by commands applies, and reason text is treated as text rather than MiniMessage markup
or a command. Public chat is never used to capture the reason.

Review the target, action, duration and reason before confirming. Permission changes
and staff hierarchy changes made while the menu is open are checked again on submit.
Closing or cancelling the menu/dialog, draft expiry, and a plugin-settings reload
cancel the draft; reopen `/punish` to start again. Repeated clicks or an old confirmation
must not create duplicate punishments. A recorded kick is still subject to the separate
online-delivery result described below.

Community Service opens a block-count menu: **100, 250, 500, 1000, 2500 or 5000**,
plus **Custom amount**. Presets display a fixed sentence amount before confirmation;
Custom opens a private form containing both the block-count field and the required
reason. Use a whole number without commas, decimal points or unit suffixes. Both
paths enforce `community-service.minimum-blocks` and `maximum-blocks` (10–10000 by
default), recheck quarry readiness and use the configured default allowed pickaxe.
An existing sentence cannot be silently overwritten or extended; use `/service add`
for additional work or `/service release` to end a sentence.

`community-service.gui-block-presets` configures the preset buttons. Only the first
six distinct in-range integer values are shown; an empty list leaves Custom available.
An existing config without this key uses the defaults. Reload with `/mp admin reload`
after changing it. A disabled or unvalidated quarry makes Community Service unavailable
in the menu. See [quarry setup](community-service.md#quarry-setup).

Revocation, sentence modification/release, history and appeal management remain
available through their existing commands. Perform the
[real-client staging checks](staging-acceptance.md#punishment-menu-with-a-real-client)
before making the menu available to staff.

## Staff hierarchy and moderation records

Set explicit staff rank nodes such as `mineplaypunishments.rank.50` and
`mineplaypunishments.rank.100`. The actor must rank **strictly above** their target.
Operators and `mineplaypunishments.protected` use the configured protected rank.
`mineplaypunishments.bypass.staff` is the audited-action override for trusted staff.
When LuckPerms is installed, its API resolves offline permissions using static server
contexts. Without it, offline local punishment requires the explicit override or
console because the plugin cannot verify offline staff rank safely. Keep staff rank
nodes in static contexts; do not rely on world/temporary context rank nodes for protection.
Website staff authorization is enforced by the website before it queues a signed-in
staff decision; the server still validates schema, target, type, bounds and revisions.

Warnings are delivered on receipt or next login. Delivery does not acknowledge them;
the player must use `/mp acknowledge`. Kicks are historical events with distinct
PENDING, EXECUTING, EXECUTED, FAILED or EXPIRED delivery state. A successful database
commit message does not claim that an offline kick was delivered.

Appeal rotation clears the presented link and durably requests a new backend token.
Until provisioned, the player sees the pending fallback. The backend invalidates the
old token when the rotation request arrives. Do not paste private appeal links into
public chat or logs. History/check commands intentionally omit them.
