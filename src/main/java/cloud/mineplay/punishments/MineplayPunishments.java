package cloud.mineplay.punishments;

import cloud.mineplay.punishments.afk.AfkManager;
import cloud.mineplay.punishments.afk.AfkSettings;
import cloud.mineplay.punishments.chat.ChatFilter;
import cloud.mineplay.punishments.chat.ChatFilterListener;
import cloud.mineplay.punishments.commands.ModerationCommands;
import cloud.mineplay.punishments.commands.PunishmentAnnouncements;
import cloud.mineplay.punishments.communityservice.CommunityServiceManager;
import cloud.mineplay.punishments.config.Messages;
import cloud.mineplay.punishments.config.PluginSettings;
import cloud.mineplay.punishments.enforcement.EnforcementListener;
import cloud.mineplay.punishments.gui.PunishMenu;
import cloud.mineplay.punishments.integration.SyncClient;
import cloud.mineplay.punishments.punishment.Models.Type;
import cloud.mineplay.punishments.punishment.PunishmentService;
import cloud.mineplay.punishments.storage.Database;
import cloud.mineplay.punishments.telemetry.TelemetryCollector;
import cloud.mineplay.punishments.telemetry.TelemetryRepository;
import cloud.mineplay.punishments.telemetry.TelemetrySettings;
import java.time.Clock;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class MineplayPunishments extends JavaPlugin {
  private Database database;
  private PunishmentService punishments;
  private CommunityServiceManager community;
  private EnforcementListener enforcement;
  private SyncClient sync;
  private TelemetryRepository telemetry;
  private TelemetryCollector telemetryCollector;
  private PunishMenu menus;
  private PunishmentAnnouncements announcements;
  private ChatFilterListener chatFilter;
  private AfkManager antiAfk;
  private PluginSettings settings;
  private volatile Messages messages;

  @Override
  public void onEnable() {
    try {
      saveDefaultConfig();
      ChatFilter initialFilter;
      try {
        initialFilter = ChatFilter.read(getConfig());
      } catch (IllegalArgumentException invalid) {
        // ChatFilter validation messages contain only controlled path/index diagnostics.
        getLogger().severe("event=chat_filter_invalid " + invalid.getMessage());
        throw invalid;
      }
      settings = PluginSettings.read(getConfig(), getDataFolder().toPath());
      messages = new Messages(this);
      announcements = new PunishmentAnnouncements(this);
      chatFilter = new ChatFilterListener(this, initialFilter);
      database = new Database(settings.database());
      database.initialize();
      punishments = new PunishmentService(database, settings.serverId(), Clock.systemUTC());
      configurePolicies();
      punishments.recoverInterruptedKicks().join();
      punishments.expire().join();
      punishments.refresh().join();
      community = new CommunityServiceManager(this, database, punishments);
      community.start();
      enforcement = new EnforcementListener(this);
      Bukkit.getPluginManager().registerEvents(enforcement, this);
      Bukkit.getPluginManager().registerEvents(chatFilter, this);
      initializeTelemetry();
      sync = createSyncClient();
      new ModerationCommands(this).register();
      menus = new PunishMenu(this);
      menus.register();
      antiAfk = new AfkManager(this, this::messages, AfkSettings.read(getConfig()));
      antiAfk.start();
      sync.start();
      Bukkit.getScheduler()
          .runTaskTimer(
              this,
              () ->
                  punishments
                      .expire()
                      .whenComplete(
                          (ignored, error) -> {
                            if (error != null) logFailure("expiration", error);
                            else onMain(enforcement::enforceAll);
                          }),
              20L,
              20L);
      enforcement.enforceAll();
      getLogger()
          .info(
              "event=plugin_ready server="
                  + settings.serverId()
                  + " store=sqlite quarry_ready="
                  + community.ready());
    } catch (Exception ex) {
      logFailure("startup_failed", ex);
      Bukkit.getScheduler().cancelTasks(this);
      stopAntiAfk();
      stopTelemetry(false);
      if (menus != null) menus.closeAll();
      if (sync != null) sync.stop();
      if (community != null) community.stop();
      org.bukkit.event.HandlerList.unregisterAll(this);
      // Remain enabled with a login guard. Disabling would silently bypass all stored bans.
      Bukkit.getPluginManager()
          .registerEvents(
              new Listener() {
                @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST)
                public void deny(AsyncPlayerPreLoginEvent event) {
                  event.disallow(
                      AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                      net.kyori.adventure.text.Component.text(
                          "Mineplay moderation storage/configuration is unavailable. Please contact"
                              + " staff."));
                }

                @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST)
                public void denyConnection(
                    io.papermc.paper.event.connection.PlayerConnectionValidateLoginEvent event) {
                  event.kickMessage(
                      net.kyori.adventure.text.Component.text(
                          "Moderation failed to initialise; staff recovery is required."));
                }

                @EventHandler
                public void denyJoin(org.bukkit.event.player.PlayerJoinEvent event) {
                  event
                      .getPlayer()
                      .kick(
                          net.kyori.adventure.text.Component.text(
                              "Moderation failed to initialise; staff recovery is required."));
                }
              },
              this);
      Bukkit.getOnlinePlayers()
          .forEach(
              player ->
                  player.kick(
                      net.kyori.adventure.text.Component.text(
                          "Moderation failed to initialise; staff recovery is required.")));
      getLogger()
          .severe(
              "event=fail_closed Login is blocked until configuration/storage is repaired and the"
                  + " server restarted.");
    }
  }

  public void reloadSettings() {
    String previous = getConfig().saveToString();
    PluginSettings previousSettings = settings;
    Messages previousMessages = messages;
    SyncClient previousSync = sync;
    SyncClient candidate = null;
    boolean stoppedPrevious = false;
    boolean applying = false;
    try {
      reloadConfig();
      ChatFilter nextFilter = ChatFilter.read(getConfig());
      PluginSettings next = PluginSettings.read(getConfig(), getDataFolder().toPath());
      if (!next.serverId().equals(settings.serverId())
          || !next.database().equals(settings.database()))
        throw new IllegalArgumentException("Restart required for database/server ID changes");
      Messages nextMessages = new Messages(this);
      applying = true;
      if (menus != null) menus.closeAll();
      community.reload();
      candidate = createSyncClient();
      previousSync.stop();
      stoppedPrevious = true;
      if (!previousSync.stoppedCleanly())
        throw new IllegalArgumentException("Previous sync is still draining; retry reload shortly");
      settings = next;
      messages = nextMessages;
      configurePolicies();
      enforcement.reload();
      sync = candidate;
      sync.start();
      telemetryCollector.reconfigure(TelemetrySettings.read(getConfig()));
      antiAfk.reconfigure(AfkSettings.read(getConfig()));
      chatFilter.replace(nextFilter);
    } catch (Exception ex) {
      if (candidate != null) candidate.stop();
      settings = previousSettings;
      messages = previousMessages;
      sync = previousSync;
      try {
        getConfig().loadFromString(previous);
        if (applying) {
          community.reload();
          configurePolicies();
          enforcement.reload();
          telemetryCollector.reconfigure(TelemetrySettings.read(getConfig()));
          antiAfk.reconfigure(AfkSettings.read(getConfig()));
        }
        if (stoppedPrevious && previousSync.stoppedCleanly()) {
          sync = createSyncClient();
          sync.start();
        }
      } catch (Exception restore) {
        logFailure("reload_restore", restore);
      }
      throw new IllegalArgumentException("Reload refused: " + ex.getMessage());
    }
  }

  @Override
  public void onDisable() {
    stopAntiAfk();
    // Capture final observations before the SQLite worker drains; reload never closes sessions.
    stopTelemetry(true);
    if (menus != null) menus.closeAll();
    if (sync != null) sync.stop();
    if (community != null) community.stop();
    if (database != null) {
      try {
        database.close();
      } catch (Exception failure) {
        logFailure("database_close", failure);
      }
    }
  }

  private SyncClient createSyncClient() {
    return new SyncClient(
        this,
        database,
        punishments,
        enforcement::enforceAll,
        community::ready,
        TelemetrySettings.read(getConfig()).enabled() ? telemetry : null);
  }

  private void stopAntiAfk() {
    try {
      if (antiAfk != null) antiAfk.stop();
    } catch (RuntimeException failure) {
      logFailure("anti_afk_shutdown_failed", failure);
    }
  }

  private void stopTelemetry(boolean shutdown) {
    try {
      if (telemetryCollector != null) telemetryCollector.stop(shutdown);
    } catch (RuntimeException failure) {
      logFailure("telemetry_shutdown_failed", failure);
    }
  }

  private void initializeTelemetry() {
    telemetry = new TelemetryRepository(database, settings.serverId());
    TelemetryCollector collector =
        new TelemetryCollector(
            this,
            telemetry,
            settings.serverId(),
            Clock.systemUTC(),
            TelemetrySettings.read(getConfig()));
    telemetryCollector = collector;
    // FIFO SQLite jobs initialize the schema before observations. Register collection now
    // so an early join/quit cannot fall through a deferred startup callback.
    telemetry
        .initialize()
        .whenComplete(
            (ignored, failure) -> {
              if (failure != null) {
                logFailure("telemetry_initialization_failed", failure);
              }
            });
    collector.start();
  }

  private void configurePolicies() {
    punishments.configureLimits(
        settings.minimumBlocks(), settings.maximumBlocks(), settings.maximumReasonLength());
    punishments.configureAppealBase(getConfig().getString("appeals.url-base"));
    for (Type type : Type.values()) {
      String path =
          switch (type) {
            case BAN -> "punishments.bans.enabled";
            case MUTE -> "punishments.mutes.enabled";
            case WARNING -> "punishments.warnings.enabled";
            case KICK -> "punishments.kicks.enabled";
            case COMMUNITY_SERVICE -> "community-service.enabled";
          };
      punishments.configureTypeEnabled(type, getConfig().getBoolean(path));
    }
  }

  public void onMain(Runnable work) {
    if (!isEnabled()) return;
    if (Bukkit.isPrimaryThread()) work.run();
    else Bukkit.getScheduler().runTask(this, work);
  }

  public void logFailure(String operation, Throwable failure) {
    Throwable root = failure;
    while (root.getCause() != null) root = root.getCause();
    // Do not log exception messages/payloads: remote strings can contain credentials.
    getLogger().severe("event=" + operation + " error_type=" + root.getClass().getSimpleName());
  }

  public PluginSettings settings() {
    return settings;
  }

  public Messages messages() {
    return messages;
  }

  public PunishmentAnnouncements announcements() {
    return announcements;
  }

  public PunishmentService punishments() {
    return punishments;
  }

  public CommunityServiceManager community() {
    return community;
  }

  public EnforcementListener enforcement() {
    return enforcement;
  }

  public SyncClient sync() {
    return sync;
  }
}
