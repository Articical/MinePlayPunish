package cloud.mineplay.punishments.integration;

import cloud.mineplay.punishments.punishment.PunishmentService;
import cloud.mineplay.punishments.storage.Database;
import java.time.*;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/** Main-thread lifecycle adapter. All configuration is copied before worker threads start. */
public final class SyncClient {
  private final SyncEngine engine;
  private final ScheduledExecutorService worker;
  private final AtomicBoolean stopped = new AtomicBoolean();
  private final AtomicBoolean manualQueued = new AtomicBoolean();
  private final JavaPlugin plugin;
  private final boolean logFailures;
  private String lastFailure = "";

  public SyncClient(
      JavaPlugin plugin,
      Database database,
      PunishmentService service,
      Runnable enforcementChanged) {
    this(plugin, database, service, enforcementChanged, () -> false);
  }

  public SyncClient(
      JavaPlugin plugin,
      Database database,
      PunishmentService service,
      Runnable enforcementChanged,
      BooleanSupplier serviceReady) {
    this.plugin = plugin;
    FileConfiguration config = plugin.getConfig();
    SyncSettings settings =
        new SyncSettings(
            config.getBoolean("website.enabled", true),
            config.getString("server.id", "mineplay-smp"),
            ApiProtocol.httpsRoot(
                config.getString(
                    "website.api-url", "https://www.mineplay.co.uk/api/moderation/server")),
            config.getString("website.credential-env", "MINEPLAY_MODERATION_API_TOKEN"),
            Duration.ofSeconds(config.getInt("website.connection-timeout-seconds", 5)),
            Duration.ofSeconds(config.getInt("website.request-timeout-seconds", 15)),
            config.getInt("website.sync-interval-seconds", 5),
            config.getInt("website.retry-backoff-seconds", 10),
            config.getInt("website.max-retry-backoff-seconds", 300),
            config.getInt("sync.batch-size", 100),
            config.getInt("punishments.kicks.remote-command-expiry-seconds", 30),
            config.getBoolean("appeals.enabled", true),
            config.getString("appeals.url-base", "https://www.mineplay.co.uk/appeal/"));
    logFailures = config.getBoolean("logging.log-api-errors", true);
    worker =
        Executors.newSingleThreadScheduledExecutor(
            task -> {
              Thread thread = new Thread(task, "MineplayPunishments-Sync");
              thread.setDaemon(true);
              return thread;
            });
    engine =
        new SyncEngine(
            database,
            service,
            settings,
            new JdkApiTransport(settings.connectTimeout(), settings.requestTimeout()),
            () -> System.getenv(settings.credentialEnvironment()),
            () -> {
              if (!stopped.get())
                plugin
                    .getServer()
                    .getScheduler()
                    .runTask(
                        plugin,
                        () -> {
                          if (!stopped.get()) enforcementChanged.run();
                        });
            },
            serviceReady,
            Clock.systemUTC(),
            Set.copyOf(config.getStringList("community-service.equipment.allowed-pickaxes")),
            config.getString("community-service.equipment.default-pickaxe", "WOODEN_PICKAXE"));
  }

  public void start() {
    worker.execute(
        () -> {
          try {
            engine.initialize();
            run(false);
          } catch (RuntimeException failure) {
            plugin
                .getLogger()
                .severe("event=sync_initialization_failed detail=LOCAL_DATABASE_UNAVAILABLE");
          }
        });
    worker.scheduleWithFixedDelay(() -> run(false), 1, 1, TimeUnit.SECONDS);
  }

  private void run(boolean manual) {
    if (stopped.get()) return;
    boolean ok = engine.cycle(manual);
    String category = engine.status().split(";", 2)[0];
    if (!ok && logFailures && !category.equals(lastFailure)) {
      plugin
          .getLogger()
          .warning(
              "event=website_sync_paused category=" + category + " local_enforcement=retained");
      lastFailure = category;
    } else if (ok) lastFailure = "";
  }

  public void trigger() {
    if (stopped.get() || !manualQueued.compareAndSet(false, true)) return;
    worker.execute(
        () -> {
          try {
            run(true);
          } finally {
            manualQueued.set(false);
          }
        });
  }

  public String status() {
    return engine.status();
  }

  /** Reload must refuse a replacement client until the previous worker has completely drained. */
  public boolean stoppedCleanly() {
    return worker.isTerminated();
  }

  public void stop() {
    stopped.set(true);
    worker.shutdownNow();
    engine.close();
    try {
      worker.awaitTermination(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
