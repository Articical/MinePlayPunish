package cloud.mineplay.punishments.afk;

import cloud.mineplay.punishments.config.Messages;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/** Sparse main-thread observation, with a single bounded async-chat signal per connection. */
public final class AfkManager implements Listener, CommandExecutor, TabCompleter {
  private static final String BYPASS = "mineplaypunishments.afk.bypass";
  private static final String CONFIRM_COMMAND = "/mineplaypunishments:mpactive ";
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final PlainTextComponentSerializer PLAIN =
      PlainTextComponentSerializer.plainText();
  private final JavaPlugin plugin;
  private final Supplier<Messages> messages;
  private final LongSupplier nanos;
  private final Supplier<String> challengeCodes;
  private final ConcurrentHashMap<UUID, Session> sessions = new ConcurrentHashMap<>();
  private volatile AfkSettings settings;
  private volatile boolean started, stopped;
  private BukkitTask task;
  private boolean kickFailureLogged;

  private static final class Session {
    final Player player;
    final AtomicReference<String> chat = new AtomicReference<>();
    AfkTracker tracker;
    boolean bypassed, attemptingIdleKick;

    Session(Player player) {
      this.player = player;
    }
  }

  public AfkManager(JavaPlugin plugin, Supplier<Messages> messages, AfkSettings settings) {
    this(
        plugin,
        messages,
        settings,
        System::nanoTime,
        () -> HexFormat.of().withUpperCase().toHexDigits(RANDOM.nextInt()));
  }

  AfkManager(
      JavaPlugin plugin,
      Supplier<Messages> messages,
      AfkSettings settings,
      LongSupplier nanos,
      Supplier<String> challengeCodes) {
    this.plugin = Objects.requireNonNull(plugin);
    this.messages = Objects.requireNonNull(messages);
    this.settings = Objects.requireNonNull(settings);
    this.nanos = Objects.requireNonNull(nanos);
    this.challengeCodes = Objects.requireNonNull(challengeCodes);
  }

  public void start() {
    mainThread();
    if (started || stopped) return;
    var command = Objects.requireNonNull(plugin.getCommand("mpactive"), "Missing command mpactive");
    command.setExecutor(this);
    command.setTabCompleter(this);
    plugin.getServer().getPluginManager().registerEvents(this, plugin);
    started = true;
    if (settings.enabled()) {
      plugin.getServer().getOnlinePlayers().forEach(this::add);
      schedule();
    }
  }

  public void reconfigure(AfkSettings replacement) {
    mainThread();
    Objects.requireNonNull(replacement);
    AfkSettings previous = settings;
    settings = replacement;
    if (!started || stopped) return;
    if (!replacement.enabled()) {
      cancelTask();
      sessions.clear();
    } else if (!previous.enabled()) {
      sessions.clear();
      plugin.getServer().getOnlinePlayers().forEach(this::add);
      schedule();
    } else if (previous.checkIntervalSeconds() != replacement.checkIntervalSeconds()) {
      cancelTask();
      schedule();
    }
  }

  private void schedule() {
    long ticks = settings.checkIntervalSeconds() * 20L;
    task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, ticks, ticks);
  }

  private void cancelTask() {
    if (task != null) task.cancel();
    task = null;
  }

  public void stop() {
    mainThread();
    stopped = true;
    started = false;
    cancelTask();
    sessions.clear();
    HandlerList.unregisterAll(this);
  }

  private void add(Player player) {
    Session session = new Session(player);
    session.bypassed = player.hasPermission(BYPASS);
    if (!session.bypassed) session.tracker = newTracker(player);
    sessions.put(player.getUniqueId(), session);
  }

  private AfkTracker newTracker(Player player) {
    AfkTracker tracker = new AfkTracker(nanos, challengeCodes);
    sample(player, tracker, false);
    return tracker;
  }

  private Session session(Player player) {
    if (!started || stopped || !settings.enabled()) return null;
    Session result = sessions.get(player.getUniqueId());
    return result != null && result.player == player ? result : null;
  }

  /** All permission and connection checks happen on the server thread. */
  private boolean eligible(Session session) {
    Player player = session.player;
    if (!player.isOnline() || plugin.getServer().getPlayer(player.getUniqueId()) != player) {
      sessions.remove(player.getUniqueId(), session);
      return false;
    }
    if (player.hasPermission(BYPASS)) {
      session.bypassed = true;
      session.tracker = null;
      session.chat.set(null);
      return false;
    }
    if (session.bypassed || session.tracker == null) {
      session.tracker = newTracker(player);
      session.bypassed = false;
      session.chat.set(null);
    }
    return true;
  }

  /** One O(online players) pass per configured interval; no movement-packet listener. */
  void tick() {
    mainThread();
    if (!started || stopped || !settings.enabled()) return;
    for (Session session : sessions.values()) {
      if (!eligible(session)) continue;
      Player player = session.player;
      String chat = session.chat.getAndSet(null);
      if (chat != null) session.tracker.chat(chat);
      Input input = player.getCurrentInput();
      boolean manual =
          !player.isInsideVehicle()
              && !player.isDead()
              && input != null
              && (input.isForward() != input.isBackward() || input.isLeft() != input.isRight());
      sample(player, session.tracker, manual);
      AfkTracker.Decision decision = session.tracker.evaluate(settings);
      switch (decision.outcome()) {
        case NONE -> {}
        case WARN -> {
          Component warning =
              messages
                  .get()
                  .text(
                      "anti-afk-warning",
                      "seconds",
                      Long.toString(decision.remainingSeconds()),
                      "code",
                      decision.code())
                  .clickEvent(ClickEvent.runCommand(CONFIRM_COMMAND + decision.code()));
          player.sendMessage(warning);
        }
        case KICK -> {
          // A protection plugin may veto this kick. Its cancellation earns another warning
          // grace, not a permanent exemption or a stream of retries every five seconds.
          session.attemptingIdleKick = true;
          try {
            player.kick(
                messages
                    .get()
                    .text(
                        "anti-afk-kick",
                        "seconds",
                        Integer.toString(settings.idleTimeoutSeconds()),
                        "minutes",
                        Integer.toString(settings.idleTimeoutSeconds() / 60)));
          } catch (RuntimeException failure) {
            session.tracker.cancelledKick();
            if (!kickFailureLogged) {
              kickFailureLogged = true;
              plugin
                  .getLogger()
                  .warning("event=anti_afk_kick_failed detail=SERVER_CALLBACK_FAILURE");
            }
          } finally {
            session.attemptingIdleKick = false;
          }
        }
      }
    }
  }

  private static void sample(Player player, AfkTracker tracker, boolean manual) {
    Location location = player.getLocation();
    if (location.getWorld() != null)
      tracker.sample(location.getWorld().getUID(), location.getX(), location.getZ(), manual);
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void join(PlayerJoinEvent event) {
    mainThread();
    if (started && !stopped && settings.enabled()) add(event.getPlayer());
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void quit(PlayerQuitEvent event) {
    mainThread();
    Session session = sessions.get(event.getPlayer().getUniqueId());
    if (session != null && session.player == event.getPlayer())
      sessions.remove(event.getPlayer().getUniqueId(), session);
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void breakBlock(BlockBreakEvent event) {
    if (!event.isCancelled()) action(event.getPlayer(), event.getBlock().getLocation());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void placeBlock(BlockPlaceEvent event) {
    if (!event.isCancelled()) action(event.getPlayer(), event.getBlockPlaced().getLocation());
  }

  private void action(Player player, Location location) {
    mainThread();
    Session session = session(player);
    if (session != null && eligible(session) && location.getWorld() != null)
      session.tracker.action(
          location.getWorld().getUID(),
          location.getBlockX(),
          location.getBlockY(),
          location.getBlockZ());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void teleport(PlayerTeleportEvent event) {
    if (!event.isCancelled()) invalidate(event.getPlayer());
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void changedWorld(PlayerChangedWorldEvent event) {
    invalidate(event.getPlayer());
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void respawn(PlayerRespawnEvent event) {
    invalidate(event.getPlayer());
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void death(PlayerDeathEvent event) {
    invalidate(event.getEntity());
  }

  private void invalidate(Player player) {
    mainThread();
    Session session = session(player);
    if (session != null && session.tracker != null) session.tracker.invalidatePosition();
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void kick(PlayerKickEvent event) {
    mainThread();
    Session session = session(event.getPlayer());
    if (event.isCancelled()
        && session != null
        && session.attemptingIdleKick
        && session.tracker != null) session.tracker.cancelledKick();
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void chat(AsyncChatEvent event) {
    if (event.isCancelled() || !started || stopped || !settings.enabled()) return;
    Player player = event.getPlayer();
    Session session = sessions.get(player.getUniqueId());
    if (session == null || session.player != player) return;
    String text = PLAIN.serialize(event.originalMessage());
    if (text.isBlank() || text.length() > 4096) return;
    // Keep neither chat text nor a per-message scheduler task. A repeating message
    // remains the same fingerprint despite whitespace/case variations.
    String normalized = text.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    if (normalized.isEmpty()) return;
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8));
      session.chat.set(HexFormat.of().formatHex(digest));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("Required SHA-256 digest unavailable");
    }
  }

  @Override
  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    mainThread();
    if (!(sender instanceof Player player)) {
      sender.sendMessage(
          messages.get().text("error", "message", "Only players can confirm activity."));
      return true;
    }
    Session session = session(player);
    if (args.length == 1
        && session != null
        && eligible(session)
        && session.tracker.confirm(args[0], settings)) {
      session.chat.set(null);
      player.sendMessage(messages.get().text("anti-afk-confirmed"));
    } else {
      player.sendMessage(messages.get().text("anti-afk-invalid"));
    }
    return true;
  }

  @Override
  public List<String> onTabComplete(
      CommandSender sender, Command command, String alias, String[] args) {
    return List.of();
  }

  private static void mainThread() {
    if (!Bukkit.isPrimaryThread())
      throw new IllegalStateException("AFK checks require the server thread");
  }
}
