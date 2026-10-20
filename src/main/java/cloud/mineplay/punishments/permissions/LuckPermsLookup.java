package cloud.mineplay.punishments.permissions;

import cloud.mineplay.punishments.config.PluginSettings;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.luckperms.api.LuckPerms;
import org.bukkit.plugin.java.JavaPlugin;

/** Separate class prevents optional LuckPerms types loading when LP is absent. */
final class LuckPermsLookup {
  private LuckPermsLookup() {}

  static CompletableFuture<Integer> rank(JavaPlugin plugin, UUID target, PluginSettings settings) {
    LuckPerms api = plugin.getServer().getServicesManager().load(LuckPerms.class);
    if (api == null)
      return CompletableFuture.failedFuture(new IllegalStateException("LuckPerms not available"));
    return api.getUserManager()
        .loadUser(target)
        .thenApply(
            user -> {
              var permissions =
                  user.getCachedData()
                      .getPermissionData(api.getContextManager().getStaticQueryOptions());
              if (permissions.checkPermission("mineplaypunishments.protected").asBoolean())
                return settings.protectedRank();
              return settings.ranks().stream()
                  .filter(
                      n -> permissions.checkPermission("mineplaypunishments.rank." + n).asBoolean())
                  .mapToInt(Integer::intValue)
                  .max()
                  .orElse(0);
            });
  }
}
