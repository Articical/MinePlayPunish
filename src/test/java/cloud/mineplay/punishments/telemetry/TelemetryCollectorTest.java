package cloud.mineplay.punishments.telemetry;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.MockedStatic;

class TelemetryCollectorTest {
  @TempDir Path level;
  private MockedStatic<Bukkit> bukkit;
  private MockedStatic<HandlerList> handlers;
  private JavaPlugin plugin;
  private Server server;
  private TelemetryRepository repository;
  private TelemetryCollector collector;
  private BukkitTask task;
  private Logger logger;
  private final List<Player> online = new ArrayList<>();
  private final List<TelemetryObservation> saved = new ArrayList<>();
  private final MutableClock clock = new MutableClock();

  @BeforeEach
  void setup() {
    bukkit = mockStatic(Bukkit.class);
    bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
    handlers = mockStatic(HandlerList.class);
    plugin = mock(JavaPlugin.class);
    server = mock(Server.class);
    logger = mock(Logger.class);
    when(plugin.getLogger()).thenReturn(logger);
    when(plugin.getServer()).thenReturn(server);
    when(server.getLevelDirectory()).thenReturn(level);
    when(server.getPluginManager()).thenReturn(mock(PluginManager.class));
    when(server.getOnlinePlayers()).thenAnswer(call -> List.copyOf(online));
    when(server.getPlayer(any(UUID.class)))
        .thenAnswer(
            call ->
                online.stream()
                    .filter(p -> p.getUniqueId().equals(call.getArgument(0)))
                    .findFirst()
                    .orElse(null));
    var scheduler = mock(BukkitScheduler.class);
    task = mock(BukkitTask.class);
    when(server.getScheduler()).thenReturn(scheduler);
    when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), eq(1L), eq(1L))).thenReturn(task);
    repository = mock(TelemetryRepository.class);
    when(repository.observe(any()))
        .thenAnswer(
            call -> {
              saved.add(call.getArgument(0));
              return CompletableFuture.completedFuture(true);
            });
    collector =
        new TelemetryCollector(
            plugin, repository, "test", clock, new TelemetrySettings(true, 30, 5));
  }

  @AfterEach
  void cleanup() {
    bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
    collector.stop(false);
    handlers.close();
    bukkit.close();
  }

  @Test
  void trueFirstJoinUsesGenuineCurrentLoginAndExplicitZeroStatistics() {
    Player player = player(false);
    when(player.getFirstPlayed()).thenReturn(0L);
    var result = collector.liveObservation(player, false, true);
    assertEquals(clock.instant(), result.firstJoinedAt());
    assertEquals(clock.instant(), result.lastJoinedAt());
    assertEquals(1L, result.loginCount());
    assertEquals(0L, result.totalPlaySeconds());
    assertNull(result.lastLeftAt());
    assertTrue(result.online());
    assertEquals(1, result.sessions().size());
  }

  @Test
  void reloadBeforeFirstPlayerSaveRetainsWitnessedJoinAndContinuesFreshSnapshots() {
    Player player = player(false);
    online.add(player);
    collector.start();
    collector.join(join(player));
    var joined = saved.getFirst();
    when(player.hasPlayedBefore()).thenReturn(true);
    collector.reconfigure(new TelemetrySettings(true, 30, 5));
    clock.advance(30);
    collector.tick();
    var refreshed = saved.getLast();
    assertEquals(clock.instant(), refreshed.observedAt());
    assertEquals(joined.firstJoinedAt(), refreshed.firstJoinedAt());
    assertEquals(
        joined.sessions().getFirst().sessionId(), refreshed.sessions().getFirst().sessionId());
    assertTrue(refreshed.online());
    assertNull(refreshed.lastLeftAt());
  }

  @Test
  void joinQuitAndReconnectUseStableDistinctSessionsAndNeverTreatReloadAsLogin() throws Exception {
    Player player = player(false);
    collector.start();
    collector.join(join(player));
    var first = saved.getFirst();
    clock.advance(60);
    collector.quit(quit(player));
    collector.tick();
    var closed = saved.getLast();
    assertFalse(closed.online());
    assertEquals(first.sessions().getFirst().sessionId(), closed.sessions().getFirst().sessionId());
    assertEquals(clock.instant(), closed.sessions().getFirst().leftAt());
    when(player.hasPlayedBefore()).thenReturn(true);
    TelemetryHistoryTest.writePlayer(
        level,
        player.getUniqueId(),
        first.firstJoinedAt().toEpochMilli(),
        first.lastJoinedAt().toEpochMilli(),
        player.getName());
    clock.advance(5);
    when(player.getLastLogin()).thenReturn(clock.instant().toEpochMilli());
    online.add(player);
    collector.join(join(player));
    drainUntil(
        () -> saved.stream().anyMatch(o -> o.online() && o.lastJoinedAt().equals(clock.instant())));
    var second = saved.getLast();
    assertNotEquals(
        first.sessions().getFirst().sessionId(), second.sessions().getFirst().sessionId());
    assertNull(second.loginCount());
    collector.reconfigure(new TelemetrySettings(true, 10, 5));
    clock.advance(10);
    drainUntil(() -> saved.stream().anyMatch(o -> o.observedAt().equals(clock.instant())));
    var refresh = saved.getLast();
    assertEquals(
        second.sessions().getFirst().sessionId(), refresh.sessions().getFirst().sessionId());
    assertNull(refresh.sessions().getFirst().leftAt());
    assertNull(refresh.loginCount());
  }

  @Test
  void returningPlayerFirstJoinComesFromRawHistoryNotConstructedApiFallback() throws Exception {
    Player player = player(true);
    Instant realFirst = clock.instant().minusSeconds(86400);
    when(player.getFirstPlayed()).thenReturn(clock.instant().toEpochMilli());
    TelemetryHistoryTest.writePlayer(
        level,
        player.getUniqueId(),
        realFirst.toEpochMilli(),
        clock.instant().minusSeconds(100).toEpochMilli(),
        player.getName());
    collector.start();
    collector.join(join(player));
    drainUntil(() -> saved.stream().anyMatch(TelemetryObservation::online));
    var result = saved.stream().filter(TelemetryObservation::online).findFirst().orElseThrow();
    assertEquals(realFirst, result.firstJoinedAt());
    assertEquals(clock.instant(), result.lastJoinedAt());
    assertNull(result.loginCount());
  }

  @Test
  void missingHistoricalFirstJoinDoesNotPublishFakeReturningPlayerHistory() throws Exception {
    Player player = player(true);
    collector.start();
    collector.join(join(player));
    for (int i = 0; i < 100; i++) {
      collector.tick();
      Thread.sleep(2);
    }
    assertTrue(saved.isEmpty());
    verify(logger).warning(contains("INPUT_TIMELINE_UNAVAILABLE"));
  }

  @Test
  void backfillsPlayersWithoutPunishmentsUsingRealHistoryAndNoInventedSessionsOrLogout()
      throws Exception {
    UUID id = UUID.randomUUID();
    Instant first = clock.instant().minusSeconds(86400), latest = clock.instant().minusSeconds(100);
    TelemetryHistoryTest.writePlayer(
        level, id, first.toEpochMilli(), latest.toEpochMilli(), ".Bedrock Player");
    OfflinePlayer player = mock(OfflinePlayer.class);
    when(player.getName()).thenReturn(".Current Bedrock");
    when(server.getOfflinePlayer(id)).thenReturn(player);
    collector.start();
    drainUntil(() -> !saved.isEmpty());
    var result = saved.getFirst();
    assertEquals(id, result.playerId());
    assertEquals(".Current Bedrock", result.playerName());
    assertEquals(first, result.firstJoinedAt());
    assertEquals(latest, result.lastJoinedAt());
    assertFalse(result.online());
    assertNull(result.lastLeftAt());
    assertNull(result.totalPlaySeconds());
    assertNull(result.loginCount());
    assertTrue(result.sessions().isEmpty());
    assertTrue(result.recentAddresses().isEmpty());
    verify(player, never()).getFirstPlayed();
    verify(player, never()).getLastSeen();
    verify(server, never()).getOfflinePlayers();
  }

  @Test
  void offlineCollectionUsesNbtNameWhenServerHasNoCachedName() throws Exception {
    UUID id = UUID.randomUUID();
    TelemetryHistoryTest.writePlayer(
        level,
        id,
        clock.instant().minusSeconds(100).toEpochMilli(),
        clock.instant().minusSeconds(5).toEpochMilli(),
        "Known_Name");
    when(server.getOfflinePlayer(id)).thenReturn(mock(OfflinePlayer.class));
    collector.start();
    drainUntil(() -> !saved.isEmpty());
    assertEquals("Known_Name", saved.getFirst().playerName());
  }

  @Test
  void reloadDoesNotManufactureLogoutAndShutdownOnlyClosesWhenServerActuallyStops() {
    Player player = player(false);
    online.add(player);
    collector.start();
    collector.join(join(player));
    collector.reconfigure(new TelemetrySettings(false, 30, 5));
    assertTrue(saved.stream().allMatch(TelemetryObservation::online));
    collector.stop(true);
    assertTrue(saved.stream().allMatch(TelemetryObservation::online));
    verify(task).cancel();
    handlers.verify(() -> HandlerList.unregisterAll(collector));
  }

  @Test
  void actualServerShutdownQueuesFinalLogoutBeforeOwnerClosesDatabase() {
    Player player = player(false);
    online.add(player);
    collector.start();
    collector.join(join(player));
    clock.advance(60);
    when(server.isStopping()).thenReturn(true);
    collector.stop(true);
    var last = saved.getLast();
    assertFalse(last.online());
    assertEquals(clock.instant(), last.lastLeftAt());
    assertEquals(clock.instant(), last.sessions().getFirst().leftAt());
  }

  @Test
  void outstandingDatabaseWriteDoesNotBlockMainThreadOrLoseQuitOnShutdown() {
    Player player = player(false);
    CompletableFuture<Boolean> blocked = new CompletableFuture<>();
    doAnswer(
            call -> {
              saved.add(call.getArgument(0));
              return saved.size() == 1 ? blocked : CompletableFuture.completedFuture(true);
            })
        .when(repository)
        .observe(any());
    collector.start();
    collector.join(join(player));
    clock.advance(5);
    collector.quit(quit(player));
    collector.tick();
    assertEquals(1, saved.size());
    assertFalse(blocked.isDone());
    collector.stop(false);
    assertEquals(2, saved.size());
    assertFalse(saved.getLast().online());
    blocked.complete(true);
  }

  @Test
  void queuedQuitAndBackfillCannotOvertakeAJoinWaitingForVerifiedHistory() throws Exception {
    Player player = player(true);
    Instant first = clock.instant().minusSeconds(86400), joined = clock.instant();
    TelemetryHistoryTest.writePlayer(
        level, player.getUniqueId(), first.toEpochMilli(), joined.toEpochMilli(), player.getName());
    when(server.getOfflinePlayer(player.getUniqueId())).thenReturn(player);
    collector.reconfigure(new TelemetrySettings(true, 30, 1));
    collector.start();
    CompletableFuture<Instant> blocked = new CompletableFuture<>();
    var field = TelemetryCollector.class.getDeclaredField("firstRequests");
    field.setAccessible(true);
    @SuppressWarnings("unchecked")
    Map<UUID, CompletableFuture<Instant>> requests =
        (Map<UUID, CompletableFuture<Instant>>) field.get(collector);
    requests.put(player.getUniqueId(), blocked);
    collector.join(join(player));
    clock.advance(1);
    collector.quit(quit(player));
    for (int i = 0; i < 100; i++) {
      collector.tick();
      Thread.sleep(2);
    }
    assertTrue(saved.isEmpty(), "Neither later quit nor backfill may overtake the waiting join");
    blocked.complete(first);
    drainUntil(() -> saved.stream().anyMatch(o -> !o.online() && o.lastLeftAt() != null));
    assertTrue(saved.getFirst().online());
    var last = saved.getLast();
    assertFalse(last.online());
    assertEquals(clock.instant(), last.lastLeftAt());
    assertEquals(joined, last.sessions().getFirst().joinedAt());
    assertEquals(clock.instant(), last.sessions().getFirst().leftAt());
  }

  @Test
  void sameTickVerifiedJoinAndQuitMergeUnknownCountersWithoutLosingTheLogout() throws Exception {
    Player player = player(true);
    Instant first = clock.instant().minusSeconds(86400), joined = clock.instant();
    collector.start();
    CompletableFuture<Instant> blocked = new CompletableFuture<>();
    var field = TelemetryCollector.class.getDeclaredField("firstRequests");
    field.setAccessible(true);
    @SuppressWarnings("unchecked")
    Map<UUID, CompletableFuture<Instant>> requests =
        (Map<UUID, CompletableFuture<Instant>>) field.get(collector);
    requests.put(player.getUniqueId(), blocked);
    collector.join(join(player));
    clock.advance(1);
    collector.quit(quit(player));
    assertTrue(saved.isEmpty(), "Join and quit must wait for verified historical first join");

    blocked.complete(first);
    // The default five-player budget processes both drafts before submitting a database write.
    assertDoesNotThrow(collector::tick);

    assertEquals(1, saved.size());
    var result = saved.getFirst();
    assertFalse(result.online());
    assertEquals(first, result.firstJoinedAt());
    assertEquals(joined, result.lastJoinedAt());
    assertEquals(clock.instant(), result.observedAt());
    assertEquals(clock.instant(), result.lastLeftAt());
    assertNull(result.totalPlaySeconds());
    assertNull(result.loginCount());
    assertEquals(1, result.sessions().size());
    assertEquals(joined, result.sessions().getFirst().joinedAt());
    assertEquals(clock.instant(), result.sessions().getFirst().leftAt());
  }

  @Test
  void queuedRefreshAndQuitRetainUnknownCountersWhileAnotherWriteIsInFlight() {
    Player player = player(false);
    online.add(player);
    CompletableFuture<Boolean> blocked = new CompletableFuture<>();
    doAnswer(
            call -> {
              saved.add(call.getArgument(0));
              return saved.size() == 1 ? blocked : CompletableFuture.completedFuture(true);
            })
        .when(repository)
        .observe(any());
    collector.start();
    collector.join(join(player));
    var joined = saved.getFirst();
    when(player.hasPlayedBefore()).thenReturn(true);

    clock.advance(30);
    collector.tick();
    assertEquals(1, saved.size(), "Refresh remains queued while the original write is pending");
    clock.advance(5);
    online.remove(player);
    collector.quit(quit(player));
    assertEquals(1, saved.size());

    blocked.complete(true);
    assertDoesNotThrow(collector::tick);

    assertEquals(2, saved.size());
    var result = saved.getLast();
    assertFalse(result.online(), "A nullable-statistic merge must not discard the queued logout");
    assertEquals(clock.instant(), result.observedAt());
    assertEquals(clock.instant(), result.lastLeftAt());
    assertNull(result.totalPlaySeconds());
    assertNull(result.loginCount());
    assertEquals(
        joined.sessions().getFirst().sessionId(), result.sessions().getFirst().sessionId());
    assertEquals(clock.instant(), result.sessions().getFirst().leftAt());
    verify(logger, never()).warning(contains("SOURCE_UNAVAILABLE"));
  }

  @Test
  void shutdownRequeuesInFlightObservationSoLateDatabaseFailureIsNotSilentlyLost() {
    CompletableFuture<Boolean> original = new CompletableFuture<>();
    doAnswer(
            call -> {
              saved.add(call.getArgument(0));
              return saved.size() == 1 ? original : CompletableFuture.completedFuture(true);
            })
        .when(repository)
        .observe(any());
    collector.start();
    collector.join(join(player(false)));
    collector.stop(false);
    assertEquals(2, saved.size());
    assertEquals(saved.getFirst(), saved.getLast());
    original.completeExceptionally(new IllegalStateException("private database details"));
  }

  @Test
  void invalidRepositoryTimelineProducesControlledDiagnostic() {
    doReturn(CompletableFuture.completedFuture(false)).when(repository).observe(any());
    collector.start();
    collector.join(join(player(false)));
    collector.tick();
    verify(logger).warning(contains("INPUT_TIMELINE_UNAVAILABLE"));
  }

  @Test
  void asynchronousCaptureIsRejectedBeforeServerAccess() {
    Player player = player(false);
    bukkit.when(Bukkit::isPrimaryThread).thenReturn(false);
    assertThrows(IllegalStateException.class, () -> collector.liveObservation(player, false, true));
    verify(player, never()).getLastLogin();
  }

  @Test
  void literalAddressUsesBytesWithoutDnsPortOrScope() throws Exception {
    assertEquals(
        "192.0.2.10",
        TelemetryCollector.address(
            new InetSocketAddress(
                InetAddress.getByAddress(new byte[] {(byte) 192, 0, 2, 10}), 25565)));
    assertNull(
        TelemetryCollector.address(InetSocketAddress.createUnresolved("private.example", 25565)));
    assertNull(TelemetryCollector.address(null));
    byte[] mapped = new byte[16];
    mapped[10] = (byte) 255;
    mapped[11] = (byte) 255;
    mapped[12] = (byte) 192;
    mapped[14] = 2;
    mapped[15] = 10;
    assertEquals(
        "192.0.2.10",
        TelemetryCollector.address(new InetSocketAddress(InetAddress.getByAddress(mapped), 123)));
  }

  @Test
  void coalescingRetainsClosedSessionsAndAllFiveNewestDistinctAddresses() {
    Player player = player(false);
    var first = collector.liveObservation(player, false, true);
    clock.advance(1);
    var closed = collector.liveObservation(player, true, false);
    var merged = TelemetryCollector.merge(first, closed);
    assertFalse(merged.online());
    assertEquals(closed.sessions(), merged.sessions());
    clock.advance(1);
    var staleOpen =
        new TelemetryObservation(
            first.playerId(),
            first.playerName(),
            clock.instant(),
            first.firstJoinedAt(),
            first.lastJoinedAt(),
            closed.lastLeftAt(),
            false,
            null,
            null,
            first.economy(),
            first.sessions(),
            List.of());
    assertEquals(closed.sessions(), TelemetryCollector.merge(merged, staleOpen).sessions());
    for (int n = 1; n <= 6; n++) {
      clock.advance(1);
      var update =
          new TelemetryObservation(
              first.playerId(),
              first.playerName(),
              clock.instant(),
              first.firstJoinedAt(),
              first.lastJoinedAt(),
              closed.lastLeftAt(),
              false,
              null,
              null,
              first.economy(),
              List.of(),
              List.of(new TelemetryObservation.Address("192.0.2." + n, clock.instant())));
      merged = TelemetryCollector.merge(merged, update);
    }
    assertEquals(5, merged.recentAddresses().size());
    assertFalse(merged.recentAddresses().stream().anyMatch(a -> a.address().equals("192.0.2.1")));
  }

  @Test
  void coalescingNewPlayerSessionsPreservesAndAdvancesKnownLoginCount() {
    Player player = player(false);
    var first = collector.liveObservation(player, false, true);
    clock.advance(1);
    var firstClosed = collector.liveObservation(player, true, false);
    var merged = TelemetryCollector.merge(first, firstClosed);
    assertEquals(1L, merged.loginCount());
    clock.advance(1);
    when(player.hasPlayedBefore()).thenReturn(true);
    when(player.getLastLogin()).thenReturn(clock.instant().toEpochMilli());
    var second = collector.liveObservation(player, false, true);
    merged = TelemetryCollector.merge(merged, second);
    assertEquals(2L, merged.loginCount());
    assertEquals(2, merged.sessions().size());
  }

  @ParameterizedTest
  @CsvSource(
      nullValues = "unknown",
      value = {
        "unknown,unknown,unknown,unknown,unknown,unknown",
        "unknown,unknown,unknown,0,0,0",
        "0,0,0,unknown,unknown,unknown",
        "unknown,0,0,unknown,0,0",
        "0,unknown,0,0,unknown,0",
        "unknown,23,23,11,unknown,11",
        "23,unknown,23,unknown,11,11",
        "11,23,23,23,11,23",
        "23,11,23,11,23,23",
        "42,42,42,42,42,42",
        "0,9223372036854775806,9223372036854775806,9223372036854775806,0,9223372036854775806",
        "9223372036854775806,0,9223372036854775806,0,9223372036854775806,9223372036854775806",
        "unknown,9223372036854775806,9223372036854775806,9223372036854775806,unknown,9223372036854775806",
        "9223372036854775806,unknown,9223372036854775806,unknown,9223372036854775806,9223372036854775806"
      })
  void coalescingPreservesUnknownStatisticsAndNeverLowersKnownCounters(
      Long previousPlay,
      Long updatePlay,
      Long expectedPlay,
      Long previousLogins,
      Long updateLogins,
      Long expectedLogins) {
    var previous = observationWithCounters(clock.instant(), previousPlay, previousLogins);
    clock.advance(1);
    var update = observationWithCounters(clock.instant(), updatePlay, updateLogins);

    var result = assertDoesNotThrow(() -> TelemetryCollector.merge(previous, update));

    assertEquals(expectedPlay, result.totalPlaySeconds());
    assertEquals(expectedLogins, result.loginCount());
    assertEquals(update.observedAt(), result.observedAt());
  }

  private TelemetryObservation observationWithCounters(
      Instant observed, Long playSeconds, Long loginCount) {
    Instant first = Instant.parse("2026-11-01T12:00:00Z");
    return new TelemetryObservation(
        UUID.fromString("00000000-0000-0000-0000-000000000001"),
        "ExamplePlayer",
        observed,
        first,
        first,
        null,
        false,
        playSeconds,
        loginCount,
        TelemetryObservation.Economy.unavailable(),
        List.of(),
        List.of());
  }

  private Player player(boolean previous) {
    Player player = mock(Player.class);
    when(player.getUniqueId()).thenReturn(UUID.randomUUID());
    when(player.getName()).thenReturn("ExamplePlayer");
    when(player.isConnected()).thenReturn(true);
    when(player.hasPlayedBefore()).thenReturn(previous);
    when(player.getFirstPlayed()).thenReturn(clock.instant().minusSeconds(1000).toEpochMilli());
    when(player.getLastLogin()).thenReturn(clock.instant().toEpochMilli());
    return player;
  }

  private PlayerJoinEvent join(Player player) {
    var event = mock(PlayerJoinEvent.class);
    when(event.getPlayer()).thenReturn(player);
    return event;
  }

  private PlayerQuitEvent quit(Player player) {
    var event = mock(PlayerQuitEvent.class);
    when(event.getPlayer()).thenReturn(player);
    return event;
  }

  private void drainUntil(java.util.function.BooleanSupplier condition) throws Exception {
    for (int i = 0; i < 1000 && !condition.getAsBoolean(); i++) {
      collector.tick();
      Thread.sleep(2);
    }
    assertTrue(condition.getAsBoolean(), "Expected telemetry observation was not persisted");
  }

  private static class MutableClock extends Clock {
    private Instant now = Instant.parse("2026-11-09T12:00:00Z");

    void advance(long seconds) {
      now = now.plusSeconds(seconds);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
