package cloud.mineplay.punishments.commands;

import cloud.mineplay.punishments.MineplayPunishments;
import cloud.mineplay.punishments.punishment.Models.Action;
import cloud.mineplay.punishments.punishment.Models.Punishment;
import cloud.mineplay.punishments.punishment.Models.Result;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;

/** Public notices for committed staff commands and GUI confirmations, on the server thread. */
public final class PunishmentAnnouncements {
  private static final int MAX_REMEMBERED = 4096;
  private final MineplayPunishments plugin;
  private final Set<UUID> announced = new LinkedHashSet<>();

  public PunishmentAnnouncements(MineplayPunishments plugin) {
    this.plugin = plugin;
  }

  public void announce(Action action, Result result, String actorName, boolean silent) {
    if (!Bukkit.isPrimaryThread())
      throw new IllegalStateException("Punishment announcements require the server thread");
    if (action == null || result == null || !result.applied() || result.punishment() == null)
      return;
    String description = description(action, result.punishment());
    if (description == null || !announced.add(action.id())) return;
    if (announced.size() > MAX_REMEMBERED) announced.remove(announced.iterator().next());
    if (silent) return;
    Punishment punishment = result.punishment();
    String target = punishment.targetName();
    if (target == null || target.isBlank()) target = punishment.targetId().toString();
    String reason =
        action.payload().has("reason")
            ? action.payload().get("reason").getAsString()
            : punishment.reason();
    Bukkit.broadcast(
        plugin
            .messages()
            .text(
                "punishment-announcement",
                "actor",
                actorName == null || actorName.isBlank() ? action.issuer() : actorName,
                "target",
                target,
                "action",
                description,
                "reason",
                reason,
                "reference",
                punishment.reference()));
  }

  static String description(Action action, Punishment p) {
    return switch (action.type()) {
      case CREATE_BAN -> "issued " + duration(p) + " ban to";
      case CREATE_MUTE -> "issued " + duration(p) + " mute to";
      case CREATE_WARNING -> "issued a warning to";
      case CREATE_KICK -> "issued a kick to";
      case CREATE_COMMUNITY_SERVICE ->
          "assigned " + action.payload().get("blocks").getAsInt() + " community service blocks to";
      case ADD_SERVICE_BLOCKS ->
          "added " + action.payload().get("blocks").getAsInt() + " community service blocks for";
      case SET_SERVICE_BLOCKS ->
          "set community service to " + action.payload().get("blocks").getAsInt() + " blocks for";
      case RELEASE_SERVICE -> "released from community service:";
      case REVOKE_PUNISHMENT ->
          "revoked the " + p.type().name().toLowerCase(Locale.ROOT).replace('_', ' ') + " for";
      case MODIFY_EXPIRATION ->
          "changed the expiry of the "
              + p.type().name().toLowerCase(Locale.ROOT).replace('_', ' ')
              + " for";
      case ROTATE_APPEAL_TOKEN -> null;
    };
  }

  private static String duration(Punishment p) {
    if (p.expiresAt() == null) return "a permanent";
    Duration period = Duration.between(p.issuedAt(), p.expiresAt());
    long seconds = Math.max(1, period.getSeconds() + (period.getNano() > 0 ? 1 : 0));
    if (seconds % 86400 == 0) return "a " + seconds / 86400 + "d";
    if (seconds % 3600 == 0) return "a " + seconds / 3600 + "h";
    if (seconds % 60 == 0) return "a " + seconds / 60 + "m";
    return "a " + seconds + "s";
  }
}
