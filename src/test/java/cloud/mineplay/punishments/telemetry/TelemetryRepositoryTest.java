package cloud.mineplay.punishments.telemetry;

import static cloud.mineplay.punishments.telemetry.TelemetryObservation.*;
import static org.junit.jupiter.api.Assertions.*;

import cloud.mineplay.punishments.storage.Database;
import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class TelemetryRepositoryTest {
  @TempDir Path folder;
  private Database database;
  private TelemetryRepository repository;
  private final UUID player = UUID.fromString("8d762441-7824-4163-9d48-6be8b60200d7");
  private final Instant first = Instant.parse("2026-01-01T00:00:00Z");
  private final Instant joined = Instant.parse("2026-11-09T10:00:00Z");
  private final Instant observed = joined.plusSeconds(60);

  @BeforeEach
  void setup() throws Exception {
    database = new Database(folder.resolve("telemetry.db"));
    database.initialize();
    repository = new TelemetryRepository(database, "mineplay-smp");
    repository.initialize().join();
  }

  @AfterEach
  void close() throws Exception {
    database.close();
  }

  private TelemetryObservation observation(
      Instant at,
      boolean online,
      Instant last,
      Instant left,
      List<Session> sessions,
      List<Address> addresses) {
    return new TelemetryObservation(
        player,
        "Example_Player",
        at,
        first,
        last,
        left,
        online,
        null,
        null,
        new Economy(Status.NOT_INSTALLED, null, null, null),
        sessions,
        addresses);
  }

  private TelemetryObservation online(Instant at) {
    return observation(
        at,
        true,
        joined,
        null,
        List.of(
            new Session(
                TelemetryRepository.sessionId("mineplay-smp", player, joined), joined, null)),
        List.of());
  }

  private List<TelemetryRepository.Pending> batch() {
    return repository.nextBatch(100, 1024 * 1024 - 1024).join();
  }

  private JsonObject json(TelemetryRepository.Pending p) {
    return JsonParser.parseString(p.json()).getAsJsonObject();
  }

  private void ack(List<TelemetryRepository.Pending> batch) {
    repository
        .acknowledge(
            batch,
            batch.stream()
                .map(TelemetryRepository.Pending::playerId)
                .collect(java.util.stream.Collectors.toSet()),
            Set.of())
        .join();
  }

  private String scalar(String query) {
    return database
        .read(
            c -> {
              try (var s = c.createStatement();
                  var r = s.executeQuery(query)) {
                return r.next() ? r.getString(1) : null;
              }
            })
        .join();
  }

  @Test
  void allKnownPlayersPublishWithoutPunishmentIdentityOrEvents() {
    repository.observe(online(observed)).join();
    var pending = batch();
    assertEquals(1, pending.size());
    assertEquals("0", scalar("SELECT count(*) FROM players"));
    assertEquals("0", scalar("SELECT count(*) FROM punishment_events"));
    assertEquals("0", scalar("SELECT count(*) FROM sync_outbox"));
    JsonObject data = json(pending.getFirst());
    assertTrue(data.get("loginCount").isJsonNull());
    assertTrue(data.get("totalPlaySeconds").isJsonNull());
    assertEquals(first, Instant.parse(data.get("firstJoinedAt").getAsString()));
    assertTrue(data.get("observedAt").getAsString().endsWith("Z"));
    assertEquals(1, data.getAsJsonArray("sessions").size());
    assertEquals(
        Set.of(
            "playerUuid",
            "playerName",
            "observedAt",
            "firstJoinedAt",
            "lastJoinedAt",
            "lastLeftAt",
            "online",
            "totalPlaySeconds",
            "loginCount",
            "economy",
            "revision",
            "sessions",
            "recentAddresses"),
        data.keySet());
  }

  @Test
  void lostResponseAndRestartPreserveExactFrozenSnapshotAndIncreasingRevisions() throws Exception {
    repository.observe(online(observed)).join();
    var original = batch();
    repository.observe(online(observed.plusSeconds(30))).join();
    assertEquals(original, batch());
    database.close();
    database = new Database(folder.resolve("telemetry.db"));
    database.initialize();
    repository = new TelemetryRepository(database, "mineplay-smp");
    repository.initialize().join();
    assertEquals(original, batch());
    ack(original);
    var newer = batch();
    assertEquals(2, newer.getFirst().revision());
    assertNotEquals(original.getFirst().json(), newer.getFirst().json());
    ack(newer);
    assertTrue(batch().isEmpty());
  }

  @Test
  void acknowledgementsCannotEraseAConcurrentLogout() {
    repository.observe(online(observed)).join();
    var original = batch();
    var session = online(observed).sessions().getFirst();
    Instant left = observed.plusSeconds(5);
    repository
        .observe(
            observation(
                left,
                false,
                joined,
                left,
                List.of(new Session(session.sessionId(), joined, left)),
                List.of()))
        .join();
    ack(original);
    var closed = batch();
    assertEquals(2, closed.getFirst().revision());
    assertEquals(
        left,
        Instant.parse(
            json(closed.getFirst())
                .getAsJsonArray("sessions")
                .get(0)
                .getAsJsonObject()
                .get("leftAt")
                .getAsString()));
    ack(closed);
    assertTrue(batch().isEmpty());
  }

  @Test
  void repeatedIdenticalObservationDoesNotAllocateRevision() {
    repository.observe(online(observed)).join();
    ack(batch());
    repository.observe(online(observed)).join();
    assertTrue(batch().isEmpty());
    assertEquals("1", scalar("SELECT revision FROM telemetry_players"));
  }

  @Test
  void historicalRosterCannotRegressKnownTimelineOrClearLogout() {
    Instant left = observed;
    repository.observe(observation(left, false, joined, left, List.of(), List.of())).join();
    ack(batch());
    assertTrue(
        repository
            .observe(observation(left.plusSeconds(1), false, joined, null, List.of(), List.of()))
            .join());
    assertEquals(left, Instant.parse(json(batch().getFirst()).get("lastLeftAt").getAsString()));
    ack(batch());
    long health = database.failureEpoch();
    assertFalse(
        repository
            .observe(
                observation(
                    left.plusSeconds(2),
                    false,
                    joined.minusSeconds(10),
                    null,
                    List.of(),
                    List.of()))
            .join());
    assertFalse(
        repository
            .observe(observation(left.minusSeconds(1), true, joined, null, List.of(), List.of()))
            .join());
    assertEquals(health, database.failureEpoch());
    assertTrue(batch().isEmpty());
  }

  @Test
  void unavailableLogoutForAnUnobservedNewerJoinIsDeferredWithoutFabrication() {
    repository.observe(observation(observed, false, joined, observed, List.of(), List.of())).join();
    ack(batch());
    assertFalse(
        repository
            .observe(
                observation(
                    observed.plusSeconds(100),
                    false,
                    observed.plusSeconds(90),
                    null,
                    List.of(),
                    List.of()))
            .join());
    assertTrue(batch().isEmpty());
    assertEquals(0, database.failureEpoch());
  }

  @Test
  void closedSessionsNeverReopenOrChangeEvenAcrossRestart() throws Exception {
    UUID session = TelemetryRepository.sessionId("mineplay-smp", player, joined);
    repository
        .observe(
            observation(
                observed,
                false,
                joined,
                observed,
                List.of(new Session(session, joined, observed)),
                List.of()))
        .join();
    ack(batch());
    assertTrue(
        repository
            .observe(
                observation(
                    observed.plusSeconds(1),
                    false,
                    joined,
                    observed,
                    List.of(new Session(session, joined, null)),
                    List.of()))
            .join());
    assertEquals("2026-11-09T10:01:00.000000Z", scalar("SELECT left_at FROM telemetry_sessions"));
    ack(batch());
    assertFalse(
        repository
            .observe(
                observation(
                    observed.plusSeconds(2),
                    false,
                    joined,
                    observed.plusSeconds(2),
                    List.of(new Session(session, joined, observed.plusSeconds(2))),
                    List.of()))
            .join());
    assertFalse(
        repository
            .observe(
                observation(
                    observed.plusSeconds(2),
                    false,
                    joined,
                    observed,
                    List.of(new Session(session, joined.minusSeconds(1), observed)),
                    List.of()))
            .join());
    assertTrue(batch().isEmpty());
    assertEquals(0, database.failureEpoch());
  }

  @Test
  void sessionHistoryPagesUseNewRevisionsAndAllUnacknowledgedClosesAreSent() {
    List<Session> sessions = new ArrayList<>();
    for (int i = 0; i < 250; i++) {
      Instant at = first.plusSeconds(i * 2);
      sessions.add(new Session(UUID.randomUUID(), at, at.plusSeconds(1)));
    }
    repository.observe(observation(observed, true, joined, null, sessions, List.of())).join();
    assertEquals(
        first.plusSeconds(499),
        Instant.parse(json(batch().getFirst()).get("lastLeftAt").getAsString()));
    Set<String> seen = new HashSet<>();
    long revision = 0;
    for (int count : List.of(100, 100, 50)) {
      var b = batch();
      assertEquals(1, b.size());
      assertTrue(b.getFirst().revision() > revision);
      revision = b.getFirst().revision();
      var items = json(b.getFirst()).getAsJsonArray("sessions");
      assertEquals(count, items.size());
      items.forEach(s -> assertTrue(seen.add(s.getAsJsonObject().get("sessionId").getAsString())));
      ack(b);
    }
    assertEquals(250, seen.size());
    assertTrue(batch().isEmpty());
  }

  @Test
  void onlyLatestThousandSessionsAreRetained() {
    List<Session> sessions = new ArrayList<>();
    for (int i = 0; i < 1000; i++) {
      Instant at = first.plusSeconds(i * 2);
      sessions.add(new Session(UUID.randomUUID(), at, at.plusSeconds(1)));
    }
    repository.observe(observation(observed, true, joined, null, sessions, List.of())).join();
    UUID latest = UUID.randomUUID();
    repository
        .observe(
            observation(
                observed.plusSeconds(1),
                true,
                joined,
                null,
                List.of(new Session(latest, joined, null)),
                List.of()))
        .join();
    assertEquals("1000", scalar("SELECT count(*) FROM telemetry_sessions"));
    assertEquals(
        "0",
        scalar(
            "SELECT count(*) FROM telemetry_sessions WHERE"
                + " joined_at='2026-01-01T00:00:00.000000Z'"));
    assertEquals(
        "1", scalar("SELECT count(*) FROM telemetry_sessions WHERE session_id='" + latest + "'"));
  }

  @Test
  void fiveAddressesAreNormalizedDeduplicatedOrderedAndLatestSeenIsPreserved() {
    for (int i = 1; i <= 7; i++)
      repository
          .observe(
              observation(
                  observed.plusSeconds(i),
                  true,
                  joined,
                  null,
                  List.of(),
                  List.of(new Address("192.0.2." + i, observed.plusSeconds(i)))))
          .join();
    repository
        .observe(
            observation(
                observed.plusSeconds(10),
                true,
                joined,
                null,
                List.of(),
                List.of(new Address("::ffff:192.0.2.7", observed.plusSeconds(1)))))
        .join();
    var array = json(batch().getFirst()).getAsJsonArray("recentAddresses");
    assertEquals(5, array.size());
    assertEquals("192.0.2.7", array.get(0).getAsJsonObject().get("address").getAsString());
    assertEquals(
        observed.plusSeconds(7),
        Instant.parse(array.get(0).getAsJsonObject().get("lastSeenAt").getAsString()));
    assertEquals("5", scalar("SELECT count(*) FROM telemetry_addresses"));
  }

  @Test
  void invalidOrPartialAcknowledgementDoesNotDiscardAnyFrozenItems() {
    repository.observe(online(observed)).join();
    var b = batch();
    assertThrows(
        IllegalArgumentException.class, () -> repository.acknowledge(b, Set.of(), Set.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> repository.acknowledge(b, Set.of(player), Set.of(player)));
    assertThrows(
        IllegalArgumentException.class,
        () -> repository.acknowledge(b, Set.of(UUID.randomUUID()), Set.of()));
    assertEquals(b, batch());
    repository.acknowledge(b, Set.of(), Set.of(player)).join();
    assertTrue(batch().isEmpty());
  }

  @Test
  void rejectedSingletonWaitsForNewObservationAndPreservesSafeDiagnostic() {
    repository.observe(online(observed)).join();
    var b = batch();
    repository.reject(b, "PLAYER_REVISION_CONFLICT").join();
    assertTrue(batch().isEmpty());
    assertEquals(
        "PLAYER_REVISION_CONFLICT", scalar("SELECT rejection_category FROM telemetry_players"));
    repository.observe(online(observed.plusSeconds(1))).join();
    assertEquals(2, batch().getFirst().revision());
    assertThrows(
        IllegalArgumentException.class, () -> repository.reject(batch(), "raw address 192.0.2.1"));
  }

  @Test
  void rejectingOldFrozenSnapshotDoesNotSuppressNewerObservation() {
    repository.observe(online(observed)).join();
    var b = batch();
    repository.observe(online(observed.plusSeconds(1))).join();
    repository.reject(b, "PLAYER_TIMELINE_CONFLICT").join();
    assertEquals(2, batch().getFirst().revision());
  }

  @Test
  void counterHistorySurvivesNullRefreshAndKnownLoginCountIncrementsOnce() {
    var one =
        new TelemetryObservation(
            player,
            "Example",
            joined,
            joined,
            joined,
            null,
            true,
            10L,
            1L,
            Economy.unavailable(),
            List.of(new Session(UUID.randomUUID(), joined, null)),
            List.of());
    repository.observe(one).join();
    ack(batch());
    Instant next = joined.plusSeconds(100);
    repository
        .observe(
            observation(
                next,
                true,
                next,
                null,
                List.of(new Session(UUID.randomUUID(), next, null)),
                List.of()))
        .join();
    var b = batch();
    assertEquals(2, json(b.getFirst()).get("loginCount").getAsLong());
    assertEquals(10, json(b.getFirst()).get("totalPlaySeconds").getAsLong());
    ack(b);
    repository
        .observe(observation(next.plusSeconds(1), true, next, null, List.of(), List.of()))
        .join();
    assertEquals(2, json(batch().getFirst()).get("loginCount").getAsLong());
  }

  @Test
  void eachServerHasIndependentRevisionsAndSessions() {
    TelemetryRepository other = new TelemetryRepository(database, "mineplay-other");
    other.initialize().join();
    repository.observe(online(observed)).join();
    repository.observe(online(observed.plusSeconds(1))).join();
    other.observe(online(observed)).join();
    assertEquals(2, batch().getFirst().revision());
    assertEquals(1, other.nextBatch(1, 10000).join().getFirst().revision());
    assertNotEquals(
        TelemetryRepository.sessionId("mineplay-smp", player, joined),
        TelemetryRepository.sessionId("mineplay-other", player, joined));
    assertEquals(
        TelemetryRepository.sessionId("mineplay-smp", player, joined),
        TelemetryRepository.sessionId("mineplay-smp", player, joined));
  }

  @Test
  void coalescedMultipleGenuineJoinsAdvanceKnownLoginCountByEveryNewSession() {
    repository
        .observe(
            new TelemetryObservation(
                player,
                "Example",
                joined,
                joined,
                joined,
                null,
                true,
                null,
                1L,
                Economy.unavailable(),
                List.of(new Session(UUID.randomUUID(), joined, null)),
                List.of()))
        .join();
    ack(batch());
    Instant second = joined.plusSeconds(10), third = joined.plusSeconds(20);
    var secondSession = new Session(UUID.randomUUID(), second, second.plusSeconds(1));
    var thirdSession = new Session(UUID.randomUUID(), third, null);
    var combined =
        new TelemetryObservation(
            player,
            "Example",
            third,
            joined,
            third,
            second.plusSeconds(1),
            true,
            null,
            null,
            Economy.unavailable(),
            List.of(secondSession, thirdSession),
            List.of());
    repository.observe(combined).join();
    assertEquals(3, json(batch().getFirst()).get("loginCount").getAsLong());
    ack(batch());
    repository.observe(combined).join();
    assertTrue(batch().isEmpty());
  }

  @Test
  void batchLimitsAreCountedInUtf8BytesAndNeverFreezeAnOversizedRequest() {
    for (int i = 0; i < 105; i++)
      repository
          .observe(
              new TelemetryObservation(
                  UUID.randomUUID(),
                  "Bedrock_名前",
                  observed,
                  first,
                  joined,
                  null,
                  true,
                  null,
                  null,
                  Economy.unavailable(),
                  List.of(),
                  List.of()))
          .join();
    var b = repository.nextBatch(100, 1024 * 1024 - 1024).join();
    assertEquals(100, b.size());
    var small = repository.nextBatch(100, 1000).join();
    int bytes =
        2
            + small.stream().mapToInt(p -> p.json().getBytes(StandardCharsets.UTF_8).length).sum()
            + Math.max(0, small.size() - 1);
    assertTrue(bytes <= 1000);
    assertTrue(small.size() < 100);
    assertThrows(IllegalArgumentException.class, () -> repository.nextBatch(101, 10000));
    assertThrows(IllegalArgumentException.class, () -> repository.nextBatch(1, 1024 * 1024));
  }

  @Test
  void earlierGenuineFirstJoinCanBeRecoveredButNeverAdvanced() {
    repository.observe(online(observed)).join();
    ack(batch());
    TelemetryObservation newer =
        new TelemetryObservation(
            player,
            "NewName",
            observed.plusSeconds(1),
            first.plusSeconds(10),
            joined,
            null,
            true,
            null,
            null,
            Economy.unavailable(),
            List.of(),
            List.of());
    repository.observe(newer).join();
    assertEquals(first, Instant.parse(json(batch().getFirst()).get("firstJoinedAt").getAsString()));
  }

  @Test
  void exhaustedRevisionIsNotResetOrWrapped() {
    repository.observe(online(observed)).join();
    ack(batch());
    database
        .write(
            c -> {
              try (var s = c.createStatement()) {
                s.executeUpdate("UPDATE telemetry_players SET revision=9223372036854775806");
              }
              return null;
            })
        .join();
    assertFalse(repository.observe(online(observed.plusSeconds(1))).join());
    assertTrue(batch().isEmpty());
    assertEquals(0, database.failureEpoch());
  }

  @Test
  void continuousOnlineRefreshesDoNotStarveLaterPlayersOrHistoricalBackfill() {
    List<UUID> online = new ArrayList<>();
    Set<UUID> historical = new HashSet<>();
    for (int i = 0; i < 25; i++) {
      UUID id = UUID.randomUUID();
      online.add(id);
      repository
          .observe(
              new TelemetryObservation(
                  id,
                  "Online",
                  observed,
                  first,
                  joined,
                  null,
                  true,
                  null,
                  null,
                  Economy.unavailable(),
                  List.of(),
                  List.of()))
          .join();
    }
    for (int i = 0; i < 5; i++) {
      UUID id = UUID.randomUUID();
      historical.add(id);
      repository
          .observe(
              new TelemetryObservation(
                  id,
                  "Historical",
                  observed,
                  first,
                  joined,
                  null,
                  false,
                  null,
                  null,
                  Economy.unavailable(),
                  List.of(),
                  List.of()))
          .join();
    }
    Set<UUID> seen = new HashSet<>();
    for (int round = 1; round <= 5; round++) {
      var b = repository.nextBatch(10, 100000).join();
      assertEquals(10, b.size());
      b.forEach(p -> seen.add(p.playerId()));
      ack(b);
      for (UUID id : online)
        repository
            .observe(
                new TelemetryObservation(
                    id,
                    "Online",
                    observed.plusSeconds(round),
                    first,
                    joined,
                    null,
                    true,
                    null,
                    null,
                    Economy.unavailable(),
                    List.of(),
                    List.of()))
            .join();
    }
    assertTrue(seen.containsAll(online));
    assertTrue(seen.containsAll(historical));
  }
}
