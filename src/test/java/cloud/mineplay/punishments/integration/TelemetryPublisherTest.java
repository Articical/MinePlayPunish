package cloud.mineplay.punishments.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cloud.mineplay.punishments.telemetry.TelemetryRepository;
import cloud.mineplay.punishments.telemetry.TelemetryRepository.Pending;
import com.google.gson.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TelemetryPublisherTest {
  TelemetryRepository repository;
  SyncEngineTest.MutableClock clock;
  SyncSettings settings;

  @BeforeEach
  void setup() {
    repository = mock(TelemetryRepository.class);
    clock = new SyncEngineTest.MutableClock();
    settings = new SyncEngineTest().settings(false);
    when(repository.initialize()).thenReturn(CompletableFuture.completedFuture(null));
    when(repository.acknowledge(anyList(), anySet(), anySet()))
        .thenReturn(CompletableFuture.completedFuture(null));
    when(repository.reject(anyList(), anyString()))
        .thenReturn(CompletableFuture.completedFuture(null));
  }

  static Pending row(UUID id, long revision) {
    JsonObject player = new JsonObject();
    player.addProperty("playerUuid", id.toString());
    player.addProperty("revision", revision);
    player.addProperty("playerName", "ExamplePlayer");
    player.addProperty("observedAt", "2026-11-09T12:00:00Z");
    player.addProperty("firstJoinedAt", "2026-10-01T12:00:00Z");
    player.addProperty("lastJoinedAt", "2026-11-09T11:00:00Z");
    player.add("lastLeftAt", JsonNull.INSTANCE);
    player.addProperty("online", true);
    player.add("totalPlaySeconds", JsonNull.INSTANCE);
    player.add("loginCount", JsonNull.INSTANCE);
    JsonObject economy = new JsonObject();
    economy.addProperty("status", "AVAILABLE");
    economy.addProperty("balance", "1250.500000");
    economy.addProperty("provider", "Example Vault provider");
    economy.addProperty("currency", "coins");
    player.add("economy", economy);
    player.add("sessions", new JsonArray());
    player.add("recentAddresses", new JsonArray());
    return new Pending(id, revision, player.toString());
  }

  static JsonObject ack(Collection<UUID> accepted, Collection<UUID> ignored) {
    JsonObject response = new JsonObject();
    response.addProperty("schemaVersion", 1);
    response.addProperty("requestId", UUID.randomUUID().toString());
    response.addProperty("serverId", "test-server");
    JsonArray yes = new JsonArray(), old = new JsonArray();
    accepted.forEach(id -> yes.add(id.toString()));
    ignored.forEach(id -> old.add(id.toString()));
    response.add("acceptedPlayerIds", yes);
    response.add("ignoredPlayerIds", old);
    return response;
  }

  @Test
  void acceptedAndIgnoredAreAcknowledgedOnlyWithCompleteDisjointCoverage() {
    Pending first = row(UUID.randomUUID(), 4), second = row(UUID.randomUUID(), 9);
    List<Pending> rows = List.of(first, second);
    when(repository.nextBatch(100, ApiProtocol.MAX_BODY_BYTES - 1024 - 32))
        .thenReturn(CompletableFuture.completedFuture(rows));
    TelemetryPublisher publisher =
        new TelemetryPublisher(
            repository,
            settings,
            clock,
            payload -> ack(Set.of(first.playerId()), Set.of(second.playerId())));
    assertEquals(SyncEngine.CycleOutcome.SUCCEEDED, publisher.cycle().outcome());
    verify(repository).acknowledge(rows, Set.of(first.playerId()), Set.of(second.playerId()));
    verify(repository, never()).reject(anyList(), anyString());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "missing",
        "overlap",
        "duplicate",
        "unknown",
        "nonuuid",
        "wrongtype",
        "unknownfield"
      })
  void malformedAcknowledgementDoesNotCommitAnyPlayer(String kind) {
    Pending first = row(UUID.randomUUID(), 4), second = row(UUID.randomUUID(), 9);
    List<Pending> rows = List.of(first, second);
    when(repository.nextBatch(anyInt(), anyInt()))
        .thenReturn(CompletableFuture.completedFuture(rows));
    JsonObject response = ack(List.of(first.playerId(), second.playerId()), List.of());
    switch (kind) {
      case "missing" -> response.getAsJsonArray("acceptedPlayerIds").remove(1);
      case "overlap" ->
          response.getAsJsonArray("ignoredPlayerIds").add(first.playerId().toString());
      case "duplicate" ->
          response.getAsJsonArray("acceptedPlayerIds").add(first.playerId().toString());
      case "unknown" ->
          response.getAsJsonArray("ignoredPlayerIds").add(UUID.randomUUID().toString());
      case "nonuuid" -> response.getAsJsonArray("ignoredPlayerIds").add("sensitive invalid value");
      case "wrongtype" -> response.addProperty("ignoredPlayerIds", false);
      case "unknownfield" -> response.addProperty("privateData", "must not be logged");
    }
    TelemetryPublisher publisher =
        new TelemetryPublisher(repository, settings, clock, payload -> response);
    assertEquals("INVALID_RESPONSE", publisher.cycle().category());
    verify(repository, never()).acknowledge(anyList(), anySet(), anySet());
    verify(repository, never()).reject(anyList(), anyString());
    assertFalse(publisher.status().contains("sensitive"));
    assertFalse(publisher.status().contains("privateData"));
  }

  @Test
  void lostResponsesRetryTheSamePlayerSnapshotIncludingDecimalRepresentation() {
    Pending row = row(UUID.randomUUID(), 7);
    when(repository.nextBatch(anyInt(), anyInt()))
        .thenReturn(CompletableFuture.completedFuture(List.of(row)));
    List<String> sent = new ArrayList<>();
    TelemetryPublisher publisher =
        new TelemetryPublisher(
            repository,
            settings,
            clock,
            payload -> {
              sent.add(payload.toString());
              if (sent.size() == 1)
                throw new ApiFailure("HTTP_TRANSIENT", 503, clock.instant().plusSeconds(30));
              return ack(Set.of(row.playerId()), Set.of());
            });
    assertEquals("HTTP_TRANSIENT", publisher.cycle().category());
    clock.now = clock.now.plusSeconds(29);
    assertEquals("HTTP_TRANSIENT", publisher.cycle().category());
    assertEquals(1, sent.size());
    clock.now = clock.now.plusSeconds(1);
    assertEquals(SyncEngine.CycleOutcome.SUCCEEDED, publisher.cycle().outcome());
    assertEquals(sent.getFirst(), sent.getLast());
    assertTrue(sent.getFirst().contains("\"balance\":\"1250.500000\""));
  }

  @Test
  void anAtomicContractRejectionIsolatesOnlyTheBadPlayerAndPreservesHealthyBackfill() {
    Pending bad = row(UUID.randomUUID(), 1), good = row(UUID.randomUUID(), 1);
    List<Pending> pending = new ArrayList<>(List.of(bad, good));
    when(repository.nextBatch(anyInt(), anyInt()))
        .thenAnswer(
            call ->
                CompletableFuture.completedFuture(
                    List.copyOf(
                        pending.subList(
                            0, Math.min(call.<Integer>getArgument(0), pending.size())))));
    when(repository.reject(anyList(), anyString()))
        .thenAnswer(
            call -> {
              pending.removeAll(call.getArgument(0));
              return CompletableFuture.completedFuture(null);
            });
    AtomicInteger sends = new AtomicInteger();
    TelemetryPublisher publisher =
        new TelemetryPublisher(
            repository,
            settings,
            clock,
            payload -> {
              sends.incrementAndGet();
              JsonArray players = payload.getAsJsonArray("players");
              if (players
                  .get(0)
                  .getAsJsonObject()
                  .get("playerUuid")
                  .getAsString()
                  .equals(bad.playerId().toString()))
                throw new ApiFailure("PLAYER_SESSION_CONFLICT", 409, null);
              return ack(Set.of(good.playerId()), Set.of());
            });
    assertEquals("PLAYER_SESSION_CONFLICT", publisher.cycle().category());
    verify(repository, never()).reject(anyList(), anyString());
    clock.now = clock.now.plusSeconds(5);
    assertEquals("PLAYER_SESSION_CONFLICT", publisher.cycle().category());
    verify(repository).reject(List.of(bad), "PLAYER_SESSION_CONFLICT");
    clock.now = clock.now.plusSeconds(5);
    assertEquals(SyncEngine.CycleOutcome.SUCCEEDED, publisher.cycle().outcome());
    verify(repository).acknowledge(List.of(good), Set.of(good.playerId()), Set.of());
    assertEquals(3, sends.get());
  }

  @Test
  void byteBudgetCountsUtf8NotJavaCharactersAndRefusesOversizedFrozenPayload() {
    Pending base = row(UUID.randomUUID(), 1);
    JsonObject value = JsonParser.parseString(base.json()).getAsJsonObject();
    value.addProperty("playerName", "\u20ac".repeat(400_000));
    Pending oversized = new Pending(base.playerId(), 1, value.toString());
    assertTrue(oversized.json().length() < ApiProtocol.MAX_BODY_BYTES);
    assertThrows(
        IllegalArgumentException.class, () -> TelemetryPublisher.payload(List.of(oversized)));
  }

  @ParameterizedTest
  @ValueSource(strings = {"sessions", "recentAddresses"})
  void refusesOverfullHistoryArrays(String field) {
    Pending base = row(UUID.randomUUID(), 1);
    JsonObject value = JsonParser.parseString(base.json()).getAsJsonObject();
    int count = field.equals("sessions") ? 101 : 6;
    for (int i = 0; i < count; i++) value.getAsJsonArray(field).add(new JsonObject());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            TelemetryPublisher.payload(List.of(new Pending(base.playerId(), 1, value.toString()))));
  }

  @Test
  void batchIsBoundedAndCannotContainDuplicatePlayerIdentities() {
    Pending row = row(UUID.randomUUID(), 1);
    assertThrows(IllegalArgumentException.class, () -> TelemetryPublisher.payload(List.of()));
    assertThrows(
        IllegalArgumentException.class, () -> TelemetryPublisher.payload(List.of(row, row)));
    List<Pending> tooMany = new ArrayList<>();
    for (int i = 0; i < 101; i++) tooMany.add(row(UUID.randomUUID(), 1));
    assertThrows(IllegalArgumentException.class, () -> TelemetryPublisher.payload(tooMany));
  }

  @Test
  void fullRepositoryArrayBudgetLeavesRoomForPlayersWrapperAndEnvelope() {
    UUID id = UUID.randomUUID();
    when(repository.nextBatch(anyInt(), anyInt()))
        .thenAnswer(
            call -> {
              int budget = call.getArgument(1);
              JsonObject player = JsonParser.parseString(row(id, 1).json()).getAsJsonObject();
              player.addProperty("playerName", "");
              int unpadded =
                  player.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
              player.addProperty("playerName", "a".repeat(budget - unpadded - 2));
              return CompletableFuture.completedFuture(
                  List.of(new Pending(id, 1, player.toString())));
            });
    TelemetryPublisher publisher =
        new TelemetryPublisher(
            repository,
            settings,
            clock,
            payload -> {
              payload.addProperty("schemaVersion", 1);
              payload.addProperty("serverId", "s".repeat(64));
              payload.addProperty("requestId", UUID.randomUUID().toString());
              assertTrue(
                  payload.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                      < ApiProtocol.MAX_BODY_BYTES);
              return ack(Set.of(id), Set.of());
            });
    assertEquals(SyncEngine.CycleOutcome.SUCCEEDED, publisher.cycle().outcome());
    verify(repository).acknowledge(anyList(), eq(Set.of(id)), eq(Set.of()));
  }
}
