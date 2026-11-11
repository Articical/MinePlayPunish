# Idle removal and AFK machines

The detector is enabled by default in 1.4.0. After 13 minutes without qualifying
activity, a player receives a chat warning with a one-time confirmation. Without
activity or confirmation, they are disconnected at about 15 minutes. A disconnect
is not a ban, warning or recorded kick punishment. They can reconnect immediately.
Community Service custody and normal logout/telemetry handling remain in place.

## Configuration

Add this section to an existing `plugins/MineplayPunishments/config.yml` to change
the defaults. A missing section uses these settings without rewriting the file:

```yaml
anti-afk:
  enabled: true
  idle-timeout-seconds: 900
  warning-seconds: 120
  check-interval-seconds: 5
```

Install the new JAR with a full restart. Subsequent setting/message changes use
`/mp admin reload`. The idle timeout supports 60–86400 seconds. The warning must be
at least five seconds and shorter than the timeout. Checks support 1–30 seconds
and cannot be farther apart than the warning period. Invalid values reject the
reload and retain the previous configuration.

Timing uses a monotonic clock. Checks occur on the server thread, so lag can delay
them. A delayed first check still gives the full warning period; a shortened
timeout on reload never shortens an already-promised warning. Reloading an enabled
detector preserves player activity histories. Disabling clears its state and stops
its timer; re-enabling, reconnecting or restarting begins a new idle period.

Customize `anti-afk-warning`, `anti-afk-confirmed`, `anti-afk-invalid` and
`anti-afk-kick` in `messages.yml`. Existing files receive readable built-in fallbacks.
The warning supplies `<seconds>` and `<code>`, and its click runs the namespaced
`/mineplaypunishments:mpactive <code>` command. Valid confirmation consumes the code;
codes are bound to the current connection. Confirmation also works while a prisoner
is awaiting inventory recovery or the quarry is unavailable.

## What counts as activity

| Observation | Effect |
| --- | --- |
| Directional controls while entering a new horizontal four-block cell | Resets idle time when sampled |
| Successful block break/place at a new coordinate | Resets idle time |
| Distinct, noncancelled public Paper chat | Resets idle time at the next check |
| Current one-time presence confirmation | Resets idle time immediately |
| Passive water movement, vehicle movement, teleport or respawn | No activity credit |
| Jumping, looking around, spinning or revisiting a short movement loop | No continuing activity credit |
| Repeated hits on mobs, item pickups, damage, generic clicks or other commands | No activity credit |
| Repeated break/place cycles at the same coordinate or repeated chat | No continuing activity credit |

The history keeps up to 256 movement cells, 256 block positions and 32 chat
fingerprints per connection. Ordinary activity and presence confirmation retain
these histories, preventing a small loop from becoming new again every idle period.
Cancelled block/chat events do not count. Teleports establish a new movement
baseline without granting activity. Opposite direction keys do not count as travel,
and vehicle movement never counts even while a direction key is held.

Players doing legitimate repetitive work, standing at a farm, trading, fishing,
crafting, building in a small area or travelling in a vehicle may receive a warning.
They can click it or type its code to stay connected. No worlds or game modes are
automatically exempt. Grant `mineplaypunishments.afk.bypass` for intended exemptions;
operators have it by default. Removing the permission starts a fresh idle period.

This is a bounded heuristic for ordinary AFK machines. Very large circuits, varied
automated actions, a client that reads and answers the warning, or automatic
reconnection can evade it. It is not a general bot detector or a persistent
reconnect ban. Test typical legitimate activities before tuning thresholds more
aggressively.

## Tick cost and plugin integration

There is one scheduled pass over tracked online players every five seconds by
default. It reads location and Paper's current input flags; there is no new
`PlayerMoveEvent` handler, entity scan, chunk load, database access or HTTP request
in the detector. Block handlers perform bounded set lookups. Async chat stores
only a normalized SHA-256 fingerprint in one coalescing slot per connection, with
no scheduled task per message and no retained chat text. Histories are bounded.

Permission, location, input and kick calls run on the server thread. A kick that
another plugin cancels receives a fresh warning and grace period before another
attempt. Default mute rules allow the confirmation; custom command blockers must
allow `/mpactive` and `/mineplaypunishments:mpactive`. Existing community-service
restrictions allow only this exact confirmation command shape.

These design bounds avoid work proportional to movement packets or nearby mobs.
They are not a measured production TPS or latency guarantee. Input semantics are
documented in the [Paper Input API](https://jd.papermc.io/paper/26.3/org/bukkit/Input.html).

## In-game acceptance checks

Use a non-operator account without the bypass permission in isolated staging.
Temporarily setting timeout/warning/check to `60`/`15`/`1` speeds up these checks:

1. Stand still, receive a clickable warning, confirm it, and verify the old code
   cannot be reused. Repeat without confirmation and verify a normal disconnect.
2. Walk/explore and mine/build at new positions. Verify activity postpones the
   warning. Check short stop-start movement with the chosen sample interval.
3. Try the server's actual water loop, minecart loop, jumping/spinning setup and
   repeated mob-farm hits. Verify warnings still occur and unattended players leave.
4. Check the warning while mining in prison and while in recovery; confirmation
   must not unlock prison commands or remove custody.
5. Confirm ordinary chat counts but blocked/repeated chat does not. Test installed
   chat renderers, command blockers and plugins that cancel kicks.
6. Grant/revoke the bypass, reload settings while a warning is active, reconnect,
   and disable/re-enable. Verify promised grace and fresh-connection isolation.

Restore the production defaults afterwards. Automated and isolated runtime evidence
is recorded in [anti-AFK verification](anti-afk-verification.md); it does not replace
these checks with a connected Minecraft client and the actual plugin stack.
