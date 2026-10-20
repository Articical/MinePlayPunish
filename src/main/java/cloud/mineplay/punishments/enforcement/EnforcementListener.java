package cloud.mineplay.punishments.enforcement;

import cloud.mineplay.punishments.MineplayPunishments;
import cloud.mineplay.punishments.commands.Arguments;
import cloud.mineplay.punishments.punishment.Models.Punishment;
import cloud.mineplay.punishments.punishment.Models.Type;
import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.connection.PlayerGameConnection;
import io.papermc.paper.connection.PlayerLoginConnection;
import io.papermc.paper.event.connection.PlayerConnectionValidateLoginEvent;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class EnforcementListener implements Listener {
  private final MineplayPunishments plugin;
  private final Set<UUID> kicksInFlight = new HashSet<>();
  private final Set<UUID> warningSessions = new HashSet<>();
  private volatile Set<String> blockedCommands = Set.of();
  private volatile boolean blockPublicChat;
  private volatile boolean blockPrivate;

  public EnforcementListener(MineplayPunishments plugin) {
    this.plugin = plugin;
    reload();
  }

  public void reload() {
    blockedCommands =
        plugin.getConfig().getStringList("punishments.mutes.blocked-commands").stream()
            .map(label -> label.toLowerCase(java.util.Locale.ROOT))
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    blockPublicChat = plugin.getConfig().getBoolean("punishments.mutes.block-public-chat");
    blockPrivate = plugin.getConfig().getBoolean("punishments.mutes.block-private-messages");
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void login(AsyncPlayerPreLoginEvent event) {
    try {
      // Serialized behind preceding local writes. Login never trusts a startup/stale snapshot.
      plugin.punishments().refresh().get(5, TimeUnit.SECONDS);
      if (!plugin.punishments().healthy()) throw new IllegalStateException();
      plugin
          .punishments()
          .active(event.getUniqueId(), Type.BAN)
          .ifPresent(
              p ->
                  event.disallow(
                      AsyncPlayerPreLoginEvent.Result.KICK_BANNED,
                      plugin.messages().punishment("ban", p)));
    } catch (Exception ex) {
      if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
      event.disallow(
          AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
          plugin.messages().text("storage-unavailable"));
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void validateConnection(PlayerConnectionValidateLoginEvent event) {
    if (!plugin.punishments().healthy()) {
      event.kickMessage(plugin.messages().text("storage-unavailable"));
      return;
    }
    UUID id = null;
    if (event.getConnection() instanceof PlayerGameConnection connection)
      id = connection.getPlayer().getUniqueId();
    else if (event.getConnection() instanceof PlayerConfigurationConnection connection)
      id = connection.getProfile().getId();
    else if (event.getConnection() instanceof PlayerLoginConnection connection
        && connection.getAuthenticatedProfile() != null)
      id = connection.getAuthenticatedProfile().getId();
    if (id != null)
      plugin
          .punishments()
          .active(id, Type.BAN)
          .ifPresent(p -> event.kickMessage(plugin.messages().punishment("ban", p)));
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void chat(AsyncChatEvent event) {
    if (!plugin.punishments().healthy()) {
      event.setCancelled(true);
      event.getPlayer().sendMessage(plugin.messages().text("storage-unavailable"));
      return;
    }
    if (blockPublicChat)
      plugin
          .punishments()
          .active(event.getPlayer().getUniqueId(), Type.MUTE)
          .ifPresent(
              p -> {
                event.setCancelled(true);
                event.getPlayer().sendMessage(plugin.messages().punishment("mute", p));
              });
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void command(PlayerCommandPreprocessEvent event) {
    if (!blockPrivate || !blockedCommands.contains(Arguments.bareCommand(event.getMessage())))
      return;
    if (!plugin.punishments().healthy()) {
      event.setCancelled(true);
      event.getPlayer().sendMessage(plugin.messages().text("storage-unavailable"));
      return;
    }
    plugin
        .punishments()
        .active(event.getPlayer().getUniqueId(), Type.MUTE)
        .ifPresent(
            p -> {
              event.setCancelled(true);
              event.getPlayer().sendMessage(plugin.messages().punishment("mute", p));
            });
  }

  @EventHandler
  public void join(PlayerJoinEvent event) {
    Player player = event.getPlayer();
    plugin
        .punishments()
        .rememberPlayer(player.getUniqueId(), player.getName())
        .whenComplete(
            (ignored, error) -> {
              if (error != null) plugin.logFailure("remember_player", error);
            });
    enforcePlayer(player);
  }

  @EventHandler
  public void quit(PlayerQuitEvent event) {
    warningSessions.remove(event.getPlayer().getUniqueId());
  }

  public void enforceAll() {
    if (!plugin.punishments().healthy()) return;
    for (Player player : Bukkit.getOnlinePlayers()) enforcePlayer(player);
    for (Punishment p : plugin.punishments().pendingKicks()) {
      if (kicksInFlight.contains(p.id())) continue;
      var deadline = plugin.punishments().kickDeadline(p.id());
      if (deadline.isEmpty() || !deadline.get().isAfter(Instant.now())) {
        plugin.punishments().markKick(p.id(), "EXPIRED");
        continue;
      }
      Player player = Bukkit.getPlayer(p.targetId());
      if (player == null) {
        plugin.punishments().markKick(p.id(), "FAILED");
        continue;
      }
      kicksInFlight.add(p.id());
      plugin
          .punishments()
          .claimKick(p.id())
          .whenComplete(
              (claimed, error) ->
                  plugin.onMain(
                      () -> {
                        if (error != null || !Boolean.TRUE.equals(claimed)) {
                          kicksInFlight.remove(p.id());
                          return;
                        }
                        if (!player.isOnline() || !deadline.get().isAfter(Instant.now())) {
                          finishKick(p.id(), player.isOnline() ? "EXPIRED" : "FAILED");
                          return;
                        }
                        player.kick(plugin.messages().punishment("kick", p));
                        Bukkit.getScheduler()
                            .runTaskLater(
                                plugin,
                                () -> finishKick(p.id(), player.isOnline() ? "FAILED" : "EXECUTED"),
                                1L);
                      }));
    }
  }

  private void finishKick(UUID id, String state) {
    plugin
        .punishments()
        .markKick(id, state)
        .whenComplete(
            (ignored, error) ->
                plugin.onMain(
                    () -> {
                      kicksInFlight.remove(id);
                      if (error != null) plugin.logFailure("kick_delivery", error);
                    }));
  }

  private void enforcePlayer(Player player) {
    if (!plugin.punishments().healthy()) {
      player.kick(plugin.messages().text("storage-unavailable"));
      return;
    }
    var ban = plugin.punishments().active(player.getUniqueId(), Type.BAN);
    if (ban.isPresent()) {
      player.kick(plugin.messages().punishment("ban", ban.get()));
      return;
    }
    plugin.community().reconcile(player);
    if (!warningSessions.add(player.getUniqueId())) return;
    plugin
        .punishments()
        .pendingWarnings(player.getUniqueId())
        .whenComplete(
            (warnings, error) ->
                plugin.onMain(
                    () -> {
                      warningSessions.remove(player.getUniqueId());
                      if (error != null || !player.isOnline() || !plugin.punishments().healthy())
                        return;
                      for (Punishment warning : warnings) {
                        var current = plugin.punishments().find(warning.id());
                        if (current.isEmpty()
                            || current.get().status()
                                != cloud.mineplay.punishments.punishment.Models.Status.ACTIVE
                            || current.get().expiresAt() != null
                                && !current.get().expiresAt().isAfter(Instant.now())) continue;
                        player.sendMessage(plugin.messages().punishment("warning", warning));
                        plugin.punishments().markWarningDelivered(warning.id());
                      }
                    }));
  }
}
