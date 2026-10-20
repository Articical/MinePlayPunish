package cloud.mineplay.punishments.permissions;

import cloud.mineplay.punishments.config.PluginSettings;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class StaffHierarchy {
  private final JavaPlugin plugin;
  private final PluginSettings settings;

  public StaffHierarchy(JavaPlugin plugin, PluginSettings settings) {
    this.plugin = plugin;
    this.settings = settings;
  }

  /** Invoked on the main thread; asynchronous LP data is only read as immutable permission data. */
  public CompletableFuture<Boolean> mayPunish(CommandSender actor, UUID targetId) {
    if (!(actor instanceof Player) || actor.hasPermission("mineplaypunishments.bypass.staff"))
      return CompletableFuture.completedFuture(true);
    if (((Player) actor).getUniqueId().equals(targetId))
      return CompletableFuture.completedFuture(false);
    Player target = Bukkit.getPlayer(targetId);
    if (target != null)
      return CompletableFuture.completedFuture(
          rank(actor::hasPermission, actor.isOp()) > rank(target::hasPermission, target.isOp()));
    boolean operator = Bukkit.getOfflinePlayer(targetId).isOp();
    if (operator)
      return CompletableFuture.completedFuture(
          rank(actor::hasPermission, actor.isOp()) > settings.protectedRank());
    if (Bukkit.getPluginManager().isPluginEnabled("LuckPerms")) {
      CompletableFuture<Boolean> result = new CompletableFuture<>();
      LuckPermsLookup.rank(plugin, targetId, settings)
          .whenComplete(
              (targetRank, error) -> {
                if (!plugin.isEnabled()) {
                  result.complete(false);
                  return;
                }
                Bukkit.getScheduler()
                    .runTask(
                        plugin,
                        () -> {
                          if (error != null || !((Player) actor).isOnline()) {
                            result.complete(false);
                            return;
                          }
                          if (actor.hasPermission("mineplaypunishments.bypass.staff")) {
                            result.complete(true);
                            return;
                          }
                          Player currentTarget = Bukkit.getPlayer(targetId);
                          int currentTargetRank =
                              currentTarget != null
                                  ? rank(currentTarget::hasPermission, currentTarget.isOp())
                                  : Bukkit.getOfflinePlayer(targetId).isOp()
                                      ? settings.protectedRank()
                                      : targetRank;
                          result.complete(
                              rank(actor::hasPermission, actor.isOp()) > currentTargetRank);
                        });
              });
      return result;
    }
    // No trustworthy offline permission provider: require an explicit override.
    return CompletableFuture.completedFuture(false);
  }

  private int rank(Predicate<String> permissions, boolean operator) {
    if (operator || permissions.test("mineplaypunishments.protected"))
      return settings.protectedRank();
    return settings.ranks().stream()
        .filter(n -> permissions.test("mineplaypunishments.rank." + n))
        .mapToInt(Integer::intValue)
        .max()
        .orElse(0);
  }
}
