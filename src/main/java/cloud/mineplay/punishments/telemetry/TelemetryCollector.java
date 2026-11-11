package cloud.mineplay.punishments.telemetry;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/** Bukkit/Vault reads run on the server thread; disk scanning and SQLite remain off that thread. */
public final class TelemetryCollector implements Listener {
  private static final int MAX_PENDING_COLLECTION = 128;
  private final JavaPlugin plugin;
  private final TelemetryRepository repository;
  private final String serverId;
  private final Clock clock;
  private final Path levelDirectory;
  private final ExecutorService historyWorker;
  private final ArrayDeque<UUID> onlineQueue = new ArrayDeque<>();
  private final Set<UUID> onlineQueued = new HashSet<>();
  private final LinkedHashMap<UUID, TelemetryObservation> pending = new LinkedHashMap<>();
  private final LinkedHashMap<UUID, Instant> verifiedFirst = new LinkedHashMap<>();
  private final LinkedHashMap<UUID, Instant> witnessedFirst = new LinkedHashMap<>();
  private final Map<UUID, CompletableFuture<Instant>> firstRequests = new HashMap<>();
  private final ArrayDeque<LiveDraft> liveDrafts = new ArrayDeque<>();
  private final Map<UUID, Integer> waitingDrafts = new HashMap<>();
  private final LinkedHashMap<UUID, TelemetryHistory.PlayerHistory> deferredHistory =
      new LinkedHashMap<>();
  private TelemetrySettings settings;
  private BukkitTask task;
  private boolean started, stopped, persistenceFailed;
  private Instant nextRefresh = Instant.MIN, nextPersistence = Instant.MIN;
  private TelemetryObservation writing;
  private CompletableFuture<Boolean> write;
  private Backfill backfill;

  private record LiveDraft(
      UUID id,
      String name,
      Instant observed,
      Instant joined,
      boolean leaving,
      Long playSeconds,
      Long loginCount,
      TelemetryObservation.Economy economy,
      String address) {}

  private static final class Backfill {
    final BlockingQueue<TelemetryHistory.PlayerHistory> ready = new ArrayBlockingQueue<>(128);
    final AtomicLong skipped = new AtomicLong();
    volatile boolean complete;
    Future<?> task;
  }

  public TelemetryCollector(
      JavaPlugin plugin,
      TelemetryRepository repository,
      String serverId,
      Clock clock,
      TelemetrySettings settings) {
    this.plugin = Objects.requireNonNull(plugin);
    this.repository = Objects.requireNonNull(repository);
    this.serverId = Objects.requireNonNull(serverId);
    this.clock = Objects.requireNonNull(clock);
    this.settings = Objects.requireNonNull(settings);
    this.levelDirectory = plugin.getServer().getLevelDirectory();
    this.historyWorker =
        Executors.newFixedThreadPool(
            2,
            work -> {
              Thread worker = new Thread(work, "MineplayPunishments-PlayerHistory");
              worker.setDaemon(true);
              return worker;
            });
  }

  public void start() {
    mainThread();
    if (started || stopped) return;
    started = true;
    plugin.getServer().getPluginManager().registerEvents(this, plugin);
    task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1, 1);
    if (settings.enabled()) beginBackfill();
  }

  public void reconfigure(TelemetrySettings replacement) {
    mainThread();
    boolean wasEnabled = settings.enabled();
    settings = Objects.requireNonNull(replacement);
    nextRefresh = Instant.MIN;
    if (!started || stopped) return;
    if (wasEnabled && !settings.enabled()) {
      cancelBackfill();
      onlineQueue.clear();
      onlineQueued.clear();
    } else if (settings.enabled()) {
      cancelBackfill();
      // Repair/reload refreshes historical source fields; deterministic session IDs are unchanged.
      verifiedFirst.clear();
      verifiedFirst.putAll(witnessedFirst);
      beginBackfill();
    }
  }

  private void beginBackfill() {
    Backfill scan = new Backfill();
    backfill = scan;
    scan.task =
        historyWorker.submit(
            () -> {
              Path data = levelDirectory.resolve("players/data");
              try {
                if (Files.isDirectory(data)) {
                  try (DirectoryStream<Path> files = Files.newDirectoryStream(data, "*.dat")) {
                    for (Path file : files) {
                      if (Thread.currentThread().isInterrupted()) return;
                      String filename = file.getFileName().toString();
                      try {
                        UUID id = UUID.fromString(filename.substring(0, filename.length() - 4));
                        if (!filename.equalsIgnoreCase(id + ".dat")) continue;
                        TelemetryHistory.PlayerHistory history =
                            TelemetryHistory.read(levelDirectory, id);
                        if (history == null || history.lastJoin() == null)
                          scan.skipped.incrementAndGet();
                        else scan.ready.put(history);
                      } catch (IOException | IllegalArgumentException failure) {
                        scan.skipped.incrementAndGet();
                      }
                    }
                  }
                }
              } catch (IOException failure) {
                scan.skipped.incrementAndGet();
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
              } finally {
                scan.complete = true;
              }
            });
  }

  private void cancelBackfill() {
    if (backfill != null && backfill.task != null) backfill.task.cancel(true);
    backfill = null;
  }

  void tick() {
    mainThread();
    if (stopped) return;
    finishWrite();
    finishLiveDrafts(settings.playersPerTick());
    int historyBudget = settings.playersPerTick();
    for (var entry : new ArrayList<>(deferredHistory.entrySet())) {
      if (historyBudget-- == 0) break;
      if (!waitingDrafts.containsKey(entry.getKey())) {
        deferredHistory.remove(entry.getKey());
        captureHistory(entry.getValue());
      }
    }
    if (settings.enabled() && pending.size() < MAX_PENDING_COLLECTION) {
      Instant now = clock.instant();
      if (!now.isBefore(nextRefresh)) {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
          UUID id = player.getUniqueId();
          if (onlineQueued.add(id)) onlineQueue.add(id);
        }
        nextRefresh = now.plusSeconds(settings.refreshSeconds());
      }
      for (int i = 0;
          i < settings.playersPerTick() && pending.size() < MAX_PENDING_COLLECTION;
          i++) {
        if (!onlineQueue.isEmpty()) {
          UUID id = onlineQueue.removeFirst();
          onlineQueued.remove(id);
          Player player = plugin.getServer().getPlayer(id);
          if (player != null && player.isConnected()) capture(player, false, false);
        } else if (backfill != null) {
          TelemetryHistory.PlayerHistory history = backfill.ready.poll();
          if (history == null) break;
          Player online = plugin.getServer().getPlayer(history.playerId());
          if (online != null && online.isConnected()) capture(online, false, false);
          else captureHistory(history);
        } else break;
      }
      if (backfill != null && backfill.complete && backfill.ready.isEmpty()) {
        long skipped = backfill.skipped.get();
        if (skipped != 0)
          plugin
              .getLogger()
              .warning(
                  "event=player_telemetry_history_incomplete skipped="
                      + skipped
                      + " detail=UNKNOWN_OR_UNREADABLE_JOIN_HISTORY");
        backfill = null;
      }
    }
    flushOne();
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void join(PlayerJoinEvent event) {
    mainThread();
    if (!stopped && started && settings.enabled()) {
      capture(event.getPlayer(), false, true);
      flushOne();
    }
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void quit(PlayerQuitEvent event) {
    mainThread();
    if (!stopped && started && settings.enabled()) {
      capture(event.getPlayer(), true, false);
      flushOne();
    }
  }

  private void capture(Player player, boolean leaving, boolean genuineJoin) {
    try {
      LiveDraft draft = liveDraft(player, leaving, genuineJoin);
      if (draft == null) return;
      Instant first = verifiedFirst.get(draft.id());
      if (first != null && !waitingDrafts.containsKey(draft.id()))
        enqueue(observation(draft, first));
      else if (liveDrafts.size() < 4096) {
        liveDrafts.add(draft);
        waitingDrafts.merge(draft.id(), 1, Integer::sum);
        requestFirst(draft.id());
      } else overflow();
    } catch (RuntimeException failure) {
      collectionFailure();
    }
  }

  TelemetryObservation liveObservation(Player player, boolean leaving, boolean genuineJoin) {
    LiveDraft draft = liveDraft(player, leaving, genuineJoin);
    if (draft == null || !verifiedFirst.containsKey(draft.id())) return null;
    return observation(draft, verifiedFirst.get(draft.id()));
  }

  private LiveDraft liveDraft(Player player, boolean leaving, boolean genuineJoin) {
    mainThread();
    Instant now = clock.instant();
    boolean firstLogin = genuineJoin && !player.hasPlayedBefore();
    Instant latest = timestamp(player.getLastLogin());
    if (latest == null && genuineJoin) latest = now;
    if (latest == null || latest.isAfter(now)) return null;
    // For returning players even CraftPlayer can retain a constructed-time fallback when
    // bukkit.firstPlayed is missing. Verify the explicit disk field before publishing.
    if (firstLogin) {
      witnessedFirst.put(player.getUniqueId(), latest);
      while (witnessedFirst.size() > 4096) witnessedFirst.pollFirstEntry();
      rememberFirst(player.getUniqueId(), latest);
    }
    Long playSeconds = null;
    try {
      int ticks = player.getStatistic(Statistic.PLAY_ONE_MINUTE);
      if (ticks > 0 || (ticks == 0 && firstLogin)) playSeconds = ticks / 20L;
    } catch (RuntimeException ignored) {
      // Missing or unsupported statistics remain unknown.
    }
    String address = address(player.getAddress());
    return new LiveDraft(
        player.getUniqueId(),
        player.getName(),
        now,
        latest,
        leaving,
        playSeconds,
        firstLogin ? 1L : null,
        TelemetryEconomy.read(plugin.getServer(), player),
        address);
  }

  private TelemetryObservation observation(LiveDraft draft, Instant first) {
    return new TelemetryObservation(
        draft.id(),
        draft.name(),
        draft.observed(),
        first,
        draft.joined(),
        draft.leaving() ? draft.observed() : null,
        !draft.leaving(),
        draft.playSeconds(),
        draft.loginCount(),
        draft.economy(),
        List.of(
            new TelemetryObservation.Session(
                TelemetryRepository.sessionId(serverId, draft.id(), draft.joined()),
                draft.joined(),
                draft.leaving() ? draft.observed() : null)),
        draft.address() == null
            ? List.of()
            : List.of(new TelemetryObservation.Address(draft.address(), draft.observed())));
  }

  private void requestFirst(UUID id) {
    if (verifiedFirst.containsKey(id)
        || firstRequests.containsKey(id)
        || firstRequests.size() >= 128
        || historyWorker.isShutdown()) return;
    firstRequests.put(
        id,
        CompletableFuture.supplyAsync(
            () -> {
              try {
                TelemetryHistory.PlayerHistory history = TelemetryHistory.read(levelDirectory, id);
                return history == null ? null : history.firstJoin();
              } catch (IOException | RuntimeException failure) {
                return null;
              }
            },
            historyWorker));
  }

  private void rememberFirst(UUID id, Instant first) {
    verifiedFirst.put(id, first);
    while (verifiedFirst.size() > 4096) verifiedFirst.pollFirstEntry();
  }

  private void finishLiveDrafts(int maximum) {
    int size = Math.min(maximum, liveDrafts.size());
    for (int i = 0; i < size; i++) {
      LiveDraft draft = liveDrafts.peekFirst();
      Instant first = verifiedFirst.get(draft.id());
      CompletableFuture<Instant> lookup = firstRequests.get(draft.id());
      if (first == null && lookup != null && lookup.isDone()) first = lookup.getNow(null);
      if (first == null && (lookup == null || !lookup.isDone())) {
        requestFirst(draft.id());
        // Preserve event order even when the per-tick budget ends at an unresolved join.
        // In particular a later quit must never overtake that join.
        break;
      }
      liveDrafts.removeFirst();
      waitingDrafts.computeIfPresent(draft.id(), (id, count) -> count <= 1 ? null : count - 1);
      if (!waitingDrafts.containsKey(draft.id())) firstRequests.remove(draft.id());
      if (first == null || first.isAfter(draft.joined())) {
        timelineFailure();
        continue;
      }
      rememberFirst(draft.id(), first);
      try {
        enqueue(observation(draft, first));
      } catch (IllegalArgumentException failure) {
        timelineFailure();
      }
    }
  }

  private void captureHistory(TelemetryHistory.PlayerHistory history) {
    if (waitingDrafts.containsKey(history.playerId())) {
      deferredHistory.put(history.playerId(), history);
      return;
    }
    try {
      Instant now = clock.instant();
      if (history.lastJoin() == null || history.lastJoin().isAfter(now)) return;
      OfflinePlayer player = plugin.getServer().getOfflinePlayer(history.playerId());
      String name = player.getName();
      if (name == null || name.isBlank()) name = history.lastKnownName();
      if (name == null || name.isBlank()) return;
      enqueue(
          new TelemetryObservation(
              history.playerId(),
              name,
              now,
              history.firstJoin(),
              history.lastJoin(),
              null,
              false,
              history.playSeconds(),
              null,
              TelemetryEconomy.read(plugin.getServer(), player),
              List.of(),
              List.of()));
    } catch (RuntimeException failure) {
      collectionFailure();
    }
  }

  private boolean collectionFailed;

  private void collectionFailure() {
    if (!collectionFailed) {
      collectionFailed = true;
      plugin
          .getLogger()
          .warning("event=player_telemetry_collection_failed detail=SOURCE_UNAVAILABLE");
    }
  }

  private void finishWrite() {
    if (write == null || !write.isDone()) return;
    try {
      if (!Boolean.TRUE.equals(write.getNow(false))) timelineFailure();
      persistenceFailed = false;
    } catch (CompletionException | CancellationException failure) {
      TelemetryObservation newer = pending.remove(writing.playerId());
      LinkedHashMap<UUID, TelemetryObservation> ordered = new LinkedHashMap<>();
      ordered.put(writing.playerId(), newer == null ? writing : merge(writing, newer));
      ordered.putAll(pending);
      pending.clear();
      pending.putAll(ordered);
      nextPersistence = clock.instant().plusSeconds(10);
      persistenceFailure();
    } finally {
      writing = null;
      write = null;
    }
  }

  private void flushOne() {
    if (write != null || pending.isEmpty() || clock.instant().isBefore(nextPersistence)) return;
    writing = pending.remove(pending.firstEntry().getKey());
    try {
      write = repository.observe(writing);
    } catch (RuntimeException failure) {
      pending.put(writing.playerId(), writing);
      writing = null;
      nextPersistence = clock.instant().plusSeconds(10);
      persistenceFailure();
    }
  }

  private void persistenceFailure() {
    if (!persistenceFailed) {
      persistenceFailed = true;
      plugin
          .getLogger()
          .warning("event=player_telemetry_persistence_paused detail=LOCAL_DATABASE_UNAVAILABLE");
    }
  }

  /** Queue final observations before the owner drains/closes the shared SQLite worker. */
  public void stop(boolean shutdown) {
    mainThread();
    if (stopped) return;
    if (shutdown && started && settings.enabled() && plugin.getServer().isStopping())
      for (Player player : plugin.getServer().getOnlinePlayers()) capture(player, true, false);
    stopped = true;
    if (task != null) task.cancel();
    HandlerList.unregisterAll(this);
    cancelBackfill();
    historyWorker.shutdownNow();
    finishLiveDrafts(liveDrafts.size());
    if (!liveDrafts.isEmpty()) {
      plugin
          .getLogger()
          .warning(
              "event=player_telemetry_history_incomplete skipped="
                  + liveDrafts.size()
                  + " detail=UNVERIFIED_FIRST_JOIN_AT_SHUTDOWN");
      liveDrafts.clear();
    }
    finishWrite();
    if (write != null && writing != null) {
      // The in-flight transaction may still fail after ticking stops. Enqueue a complete
      // idempotent follow-up now, before the owner starts draining/closing SQLite.
      TelemetryObservation newer = pending.remove(writing.playerId());
      LinkedHashMap<UUID, TelemetryObservation> ordered = new LinkedHashMap<>();
      ordered.put(writing.playerId(), newer == null ? writing : merge(writing, newer));
      ordered.putAll(pending);
      pending.clear();
      pending.putAll(ordered);
      writing = null;
      write = null;
    }
    // Submission is nonblocking; Database.close drains these jobs after this method returns.
    while (!pending.isEmpty()) {
      TelemetryObservation observation = pending.remove(pending.firstEntry().getKey());
      try {
        repository
            .observe(observation)
            .whenComplete(
                (accepted, error) -> {
                  if (error != null)
                    plugin
                        .getLogger()
                        .severe(
                            "event=player_telemetry_shutdown_write_failed"
                                + " detail=LOCAL_DATABASE_UNAVAILABLE");
                  else if (!Boolean.TRUE.equals(accepted))
                    plugin
                        .getLogger()
                        .warning(
                            "event=player_telemetry_history_incomplete"
                                + " detail=INPUT_TIMELINE_UNAVAILABLE");
                });
      } catch (RuntimeException failure) {
        plugin
            .getLogger()
            .severe(
                "event=player_telemetry_shutdown_write_failed detail=LOCAL_DATABASE_UNAVAILABLE");
      }
    }
  }

  private static Instant timestamp(long millis) {
    return millis <= 0 ? null : Instant.ofEpochMilli(millis);
  }

  private long overflowCount;

  private void enqueue(TelemetryObservation observation) {
    TelemetryObservation previous = pending.get(observation.playerId());
    if (previous != null) {
      pending.put(observation.playerId(), merge(previous, observation));
    } else if (pending.size() < 4096) {
      pending.put(observation.playerId(), observation);
    } else overflow();
  }

  private void overflow() {
    if (overflowCount++ == 0) {
      plugin
          .getLogger()
          .severe("event=player_telemetry_queue_full detail=SESSION_HISTORY_INCOMPLETE");
    }
  }

  private long invalidTimelineCount;

  private void timelineFailure() {
    if (invalidTimelineCount++ == 0)
      plugin
          .getLogger()
          .warning("event=player_telemetry_history_incomplete detail=INPUT_TIMELINE_UNAVAILABLE");
  }

  /** Coalesce unsaved observations without losing recent session closes or addresses. */
  static TelemetryObservation merge(TelemetryObservation previous, TelemetryObservation update) {
    if (update.observedAt().isBefore(previous.observedAt())) return previous;
    TelemetryObservation current =
        update.lastJoinedAt().isBefore(previous.lastJoinedAt()) ? previous : update;
    Map<UUID, TelemetryObservation.Session> sessions = new HashMap<>();
    previous.sessions().forEach(session -> sessions.put(session.sessionId(), session));
    long additionalLogins = 0;
    for (var session : update.sessions()) {
      var existing = sessions.get(session.sessionId());
      if (existing == null) {
        sessions.put(session.sessionId(), session);
        if (session.joinedAt().isAfter(previous.lastJoinedAt())) additionalLogins++;
      } else if (existing.leftAt() == null && existing.joinedAt().equals(session.joinedAt())) {
        sessions.put(session.sessionId(), session);
      }
    }
    List<TelemetryObservation.Session> retainedSessions =
        sessions.values().stream()
            .sorted(
                Comparator.comparing(TelemetryObservation.Session::joinedAt)
                    .reversed()
                    .thenComparing(TelemetryObservation.Session::sessionId))
            .limit(1000)
            .toList();
    Map<String, TelemetryObservation.Address> addresses = new HashMap<>();
    previous.recentAddresses().forEach(address -> addresses.put(address.address(), address));
    update
        .recentAddresses()
        .forEach(
            address ->
                addresses.merge(
                    address.address(),
                    address,
                    (old, next) -> old.lastSeenAt().isAfter(next.lastSeenAt()) ? old : next));
    List<TelemetryObservation.Address> retainedAddresses =
        addresses.values().stream()
            .sorted(
                Comparator.comparing(TelemetryObservation.Address::lastSeenAt)
                    .reversed()
                    .thenComparing(TelemetryObservation.Address::address))
            .limit(5)
            .toList();
    Instant first =
        previous.firstJoinedAt().isBefore(update.firstJoinedAt())
            ? previous.firstJoinedAt()
            : update.firstJoinedAt();
    Instant left = previous.lastLeftAt();
    if (left == null || (update.lastLeftAt() != null && update.lastLeftAt().isAfter(left)))
      left = update.lastLeftAt();
    Long count = greater(previous.loginCount(), update.loginCount());
    if (previous.loginCount() != null
        && update.loginCount() == null
        && additionalLogins > 0
        && previous.loginCount() < Long.MAX_VALUE - additionalLogins)
      count = previous.loginCount() + additionalLogins;
    return new TelemetryObservation(
        current.playerId(),
        current.playerName(),
        update.observedAt(),
        first,
        current.lastJoinedAt(),
        left,
        current.online(),
        greater(previous.totalPlaySeconds(), update.totalPlaySeconds()),
        count,
        current.economy(),
        retainedSessions,
        retainedAddresses);
  }

  private static Long greater(Long a, Long b) {
    // Keep nullable branches separate: a numeric ternary would unbox an unknown value.
    if (a == null) return b;
    if (b == null) return a;
    return Math.max(a, b);
  }

  static String address(InetSocketAddress socket) {
    if (socket == null || socket.getAddress() == null) return null;
    try {
      // Recreate from bytes: no DNS, scopes, ports or host-name formatting can enter the payload.
      return InetAddress.getByAddress(socket.getAddress().getAddress()).getHostAddress();
    } catch (IOException impossible) {
      return null;
    }
  }

  private static void mainThread() {
    if (!Bukkit.isPrimaryThread())
      throw new IllegalStateException("Player telemetry collection requires the server thread");
  }
}
