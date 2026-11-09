package cloud.mineplay.punishments.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cloud.mineplay.punishments.punishment.PunishmentService;
import cloud.mineplay.punishments.storage.Database;
import cloud.mineplay.punishments.telemetry.TelemetryObservation;
import cloud.mineplay.punishments.telemetry.TelemetryRepository;
import cloud.mineplay.punishments.telemetry.TelemetryRepository.Pending;
import com.google.gson.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SyncTelemetryTest {
  @TempDir Path temp;
  Database database;
  PunishmentService service;
  TelemetryRepository repository;
  SyncEngineTest.MutableClock clock;
  Pending row;
  SyncSettings settings;
  final List<SyncEngine> engines = new ArrayList<>();

  @BeforeEach
  void setup() throws Exception {
    database = new Database(temp.resolve("telemetry-publisher.db"));
    database.initialize();
    clock = new SyncEngineTest.MutableClock();
    settings = new SyncEngineTest().settings(false);
    service = new PunishmentService(database, "test-server", clock);
    service.refresh().join();
    repository = mock(TelemetryRepository.class);
    row = TelemetryPublisherTest.row(UUID.randomUUID(), 19);
    when(repository.initialize()).thenReturn(CompletableFuture.completedFuture(null));
    when(repository.nextBatch(anyInt(), anyInt()))
        .thenReturn(CompletableFuture.completedFuture(List.of(row)));
    when(repository.acknowledge(anyList(), anySet(), anySet()))
        .thenReturn(CompletableFuture.completedFuture(null));
    when(repository.reject(anyList(), anyString()))
        .thenReturn(CompletableFuture.completedFuture(null));
  }

  @AfterEach
  void cleanup() throws Exception {
    engines.forEach(SyncEngine::close);
    database.close();
  }

  SyncEngine engine(ApiTransport transport, ApiRequestGate gate) {
    SyncEngine engine =
        new SyncEngine(
            database,
            service,
            settings,
            transport,
            () -> "private-rotatable-credential",
            () -> {},
            () -> true,
            clock,
            Set.of("WOODEN_PICKAXE"),
            "WOODEN_PICKAXE",
            repository,
            gate);
    engine.initialize();
    engines.add(engine);
    return engine;
  }

  JsonObject envelope(Map<String, String> headers) {
    JsonObject response = new JsonObject();
    response.addProperty("schemaVersion", 1);
    response.addProperty("requestId", headers.get("X-Request-Id"));
    response.addProperty("serverId", headers.get("X-Server-Id"));
    return response;
  }

  ApiTransport.Response success(URI uri, Map<String, String> headers, String body) {
    JsonObject response = envelope(headers);
    if (uri.getPath().endsWith("/players")) {
      JsonArray ids = new JsonArray();
      JsonParser.parseString(body)
          .getAsJsonObject()
          .getAsJsonArray("players")
          .forEach(player -> ids.add(player.getAsJsonObject().get("playerUuid")));
      response.add("acceptedPlayerIds", ids);
      response.add("ignoredPlayerIds", new JsonArray());
    } else {
      response.add("actions", new JsonArray());
      response.addProperty("cursor", "cursor-1");
      response.addProperty("hasMore", false);
      response.addProperty("snapshotRevision", "snapshot-1");
    }
    return new ApiTransport.Response(200, response.toString());
  }

  @Test
  void sendsExactPlayerEndpointAndHeadersWithoutUsingPunishmentEvents() {
    List<String> paths = new ArrayList<>();
    AtomicInteger requests = new AtomicInteger();
    SyncEngine engine =
        engine(
            (method, uri, headers, body) -> {
              paths.add(uri.getPath());
              requests.incrementAndGet();
              assertEquals("Bearer private-rotatable-credential", headers.get("Authorization"));
              assertEquals("test-server", headers.get("X-Server-Id"));
              assertEquals("application/json", headers.get("Content-Type"));
              assertDoesNotThrow(() -> UUID.fromString(headers.get("X-Request-Id")));
              if (uri.getPath().endsWith("/players")) {
                assertEquals("POST", method);
                assertEquals(
                    "https://www.mineplay.co.uk/api/moderation/server/players", uri.toString());
                JsonObject payload = JsonParser.parseString(body).getAsJsonObject();
                assertEquals(headers.get("X-Request-Id"), payload.get("requestId").getAsString());
                assertEquals(headers.get("X-Server-Id"), payload.get("serverId").getAsString());
                assertEquals(1, payload.get("schemaVersion").getAsInt());
                assertEquals(
                    Set.of("schemaVersion", "requestId", "serverId", "players"), payload.keySet());
                assertEquals(
                    JsonParser.parseString(row.json()), payload.getAsJsonArray("players").get(0));
                JsonPrimitive balance =
                    payload
                        .getAsJsonArray("players")
                        .get(0)
                        .getAsJsonObject()
                        .getAsJsonObject("economy")
                        .getAsJsonPrimitive("balance");
                assertTrue(balance.isString());
                assertEquals("1250.500000", balance.getAsString());
                assertTrue(
                    body.getBytes(StandardCharsets.UTF_8).length < ApiProtocol.MAX_BODY_BYTES);
              }
              return CompletableFuture.completedFuture(success(uri, headers, body));
            },
            ApiRequestGate.unpaced(clock));
    assertTrue(engine.cycle(true));
    assertEquals(2, requests.get());
    assertTrue(paths.stream().anyMatch(path -> path.endsWith("/players")));
    assertFalse(paths.stream().anyMatch(path -> path.endsWith("/events")));
    verify(repository).acknowledge(List.of(row), Set.of(row.playerId()), Set.of());
  }

  @Test
  void restartAndLostResponseChangeOnlyEnvelopeNotFrozenPlayerRevision() {
    List<JsonObject> requests = new ArrayList<>();
    ApiTransport transport =
        (method, uri, headers, body) -> {
          if (uri.getPath().endsWith("/players")) {
            requests.add(JsonParser.parseString(body).getAsJsonObject());
            if (requests.size() == 1)
              return CompletableFuture.failedFuture(
                  new java.io.IOException("do not log sensitive network details"));
          }
          return CompletableFuture.completedFuture(success(uri, headers, body));
        };
    SyncEngine first = engine(transport, ApiRequestGate.unpaced(clock));
    assertFalse(first.cycle(true));
    first.close();
    SyncEngine second = engine(transport, ApiRequestGate.unpaced(clock));
    assertTrue(second.cycle(true));
    assertNotEquals(requests.get(0).get("requestId"), requests.get(1).get("requestId"));
    assertEquals(requests.get(0).get("players"), requests.get(1).get("players"));
    verify(repository, times(1)).acknowledge(List.of(row), Set.of(row.playerId()), Set.of());
  }

  @ParameterizedTest
  @CsvSource({
    "400,INVALID_VALUE,PLAYER_HTTP_400",
    "409,PLAYER_REVISION_CONFLICT,PLAYER_REVISION_CONFLICT",
    "409,PLAYER_OBSERVATION_CONFLICT,PLAYER_OBSERVATION_CONFLICT",
    "409,PLAYER_TIMELINE_CONFLICT,PLAYER_TIMELINE_CONFLICT",
    "409,PLAYER_SESSION_CONFLICT,PLAYER_SESSION_CONFLICT",
    "409,untrusted-secret,PLAYER_HTTP_409"
  })
  void permanentTelemetryErrorsAreDiagnosedWithoutLeakingResponseData(
      int status, String code, String expected) {
    SyncEngine engine =
        engine(
            (method, uri, headers, body) -> {
              if (uri.getPath().endsWith("/players")) {
                JsonObject error = new JsonObject();
                error.addProperty("code", code);
                error.addProperty("message", "raw IP history and bearer secret must never appear");
                JsonObject envelope = new JsonObject();
                envelope.add("error", error);
                return CompletableFuture.completedFuture(
                    new ApiTransport.Response(status, envelope.toString()));
              }
              return CompletableFuture.completedFuture(success(uri, headers, body));
            },
            ApiRequestGate.unpaced(clock));
    assertEquals(expected, engine.runCycle(true).category());
    verify(repository).reject(List.of(row), expected);
    assertTrue(engine.status().contains(expected));
    assertFalse(engine.status().contains("secret"));
    assertFalse(engine.status().contains("raw IP"));
  }

  @ParameterizedTest
  @CsvSource({"429,600", "503,Mon; 09 Nov 2026 12:10:00 GMT"})
  void retryAfterAppliesToEveryLaneAndReplacementClient(int status, String retryAfter) {
    clock.now = Instant.parse("2026-11-09T12:00:00Z");
    AtomicInteger requests = new AtomicInteger();
    ApiRequestGate shared = ApiRequestGate.unpaced(clock);
    ApiTransport transport =
        (method, uri, headers, body) -> {
          if (requests.incrementAndGet() == 1)
            return CompletableFuture.completedFuture(
                new ApiTransport.Response(status, "private error", retryAfter.replace(';', ',')));
          return CompletableFuture.completedFuture(success(uri, headers, body));
        };
    SyncEngine first = engine(transport, shared);
    assertEquals("HTTP_TRANSIENT", first.runCycle(true).category());
    assertEquals(1, requests.get(), "No later lane may send through server backoff");
    first.close();
    SyncEngine replacement = engine(transport, shared);
    clock.now = clock.now.plusSeconds(599);
    assertEquals(SyncEngine.CycleOutcome.SKIPPED, replacement.runCycle(true).outcome());
    assertEquals(1, requests.get());
    clock.now = clock.now.plusSeconds(1);
    assertTrue(replacement.cycle(true));
    assertEquals(3, requests.get());
  }

  @Test
  void telemetryBackoffDoesNotDelayModerationPolling() {
    AtomicInteger moderation = new AtomicInteger(), telemetry = new AtomicInteger();
    SyncEngine engine =
        engine(
            (method, uri, headers, body) -> {
              if (uri.getPath().endsWith("/players")) {
                telemetry.incrementAndGet();
                return CompletableFuture.completedFuture(
                    new ApiTransport.Response(500, "private error"));
              }
              moderation.incrementAndGet();
              return CompletableFuture.completedFuture(success(uri, headers, body));
            },
            ApiRequestGate.unpaced(clock));
    assertEquals("HTTP_TRANSIENT", engine.runCycle(false).category());
    assertEquals(1, moderation.get());
    assertEquals(1, telemetry.get());
    clock.now = clock.now.plusSeconds(5);
    assertEquals("HTTP_TRANSIENT", engine.runCycle(false).category());
    assertEquals(2, moderation.get());
    assertEquals(1, telemetry.get());
    clock.now = clock.now.plusSeconds(5);
    engine.runCycle(false);
    assertEquals(3, moderation.get());
    assertEquals(2, telemetry.get());
  }

  @Test
  void invalidAcknowledgementIdentityLeavesTheFrozenSnapshotPending() {
    SyncEngine engine =
        engine(
            (method, uri, headers, body) -> {
              ApiTransport.Response response = success(uri, headers, body);
              if (uri.getPath().endsWith("/players")) {
                JsonObject invalid = JsonParser.parseString(response.body()).getAsJsonObject();
                invalid.addProperty("requestId", UUID.randomUUID().toString());
                response = new ApiTransport.Response(200, invalid.toString());
              }
              return CompletableFuture.completedFuture(response);
            },
            ApiRequestGate.unpaced(clock));
    assertEquals("INVALID_RESPONSE", engine.runCycle(true).category());
    verify(repository, never()).acknowledge(anyList(), anySet(), anySet());
    verify(repository, never()).reject(anyList(), anyString());
  }

  @Test
  void realPersistentQueueRetriesLostOnlineSnapshotThenPublishesTheNewClosedSession() {
    repository = new TelemetryRepository(database, "test-server");
    repository.initialize().join();
    UUID player = UUID.randomUUID(), session = UUID.randomUUID();
    Instant joined = clock.instant().minusSeconds(60), first = joined.minusSeconds(3600);
    TelemetryObservation.Economy economy =
        new TelemetryObservation.Economy(
            TelemetryObservation.Status.AVAILABLE, "1250.500000", "Example provider", "coins");
    repository
        .observe(
            new TelemetryObservation(
                player,
                "UnpunishedPlayer",
                clock.instant(),
                first,
                joined,
                null,
                true,
                null,
                null,
                economy,
                List.of(new TelemetryObservation.Session(session, joined, null)),
                List.of(new TelemetryObservation.Address("192.0.2.10", joined))))
        .join();
    List<JsonObject> delivered = new ArrayList<>();
    ApiTransport transport =
        (method, uri, headers, body) -> {
          if (uri.getPath().endsWith("/players")) {
            JsonObject snapshot =
                JsonParser.parseString(body)
                    .getAsJsonObject()
                    .getAsJsonArray("players")
                    .get(0)
                    .getAsJsonObject();
            delivered.add(snapshot);
            if (delivered.size() == 1) {
              clock.now = clock.now.plusSeconds(1);
              repository
                  .observe(
                      new TelemetryObservation(
                          player,
                          "UnpunishedPlayer",
                          clock.instant(),
                          first,
                          joined,
                          clock.instant(),
                          false,
                          null,
                          null,
                          economy,
                          List.of(
                              new TelemetryObservation.Session(session, joined, clock.instant())),
                          List.of()))
                  .join();
              return CompletableFuture.failedFuture(
                  new java.io.IOException("Lost committed response"));
            }
          }
          return CompletableFuture.completedFuture(success(uri, headers, body));
        };
    SyncEngine firstEngine = engine(transport, ApiRequestGate.unpaced(clock));
    assertFalse(firstEngine.cycle(true));
    firstEngine.close();
    SyncEngine restarted = engine(transport, ApiRequestGate.unpaced(clock));
    assertTrue(restarted.cycle(true));
    assertEquals(delivered.get(0), delivered.get(1));
    clock.now = clock.now.plusSeconds(5);
    assertTrue(restarted.cycle(true));
    assertEquals(3, delivered.size());
    JsonObject closed = delivered.get(2);
    assertTrue(closed.get("revision").getAsLong() > delivered.get(0).get("revision").getAsLong());
    assertFalse(closed.get("online").getAsBoolean());
    assertFalse(
        closed.getAsJsonArray("sessions").get(0).getAsJsonObject().get("leftAt").isJsonNull());
    assertEquals(
        session.toString(),
        closed.getAsJsonArray("sessions").get(0).getAsJsonObject().get("sessionId").getAsString());
    assertTrue(repository.nextBatch(100, ApiProtocol.MAX_BODY_BYTES - 1024).join().isEmpty());
    assertEquals(
        0,
        database
            .read(
                c -> {
                  try (var sql = c.createStatement();
                      var rows = sql.executeQuery("SELECT COUNT(*) FROM sync_outbox")) {
                    return rows.getInt(1);
                  }
                })
            .join());
  }

  @Test
  void telemetryInitializationFailureDoesNotPreventModerationStartupAndRetriesSeparately() {
    when(repository.initialize())
        .thenReturn(
            CompletableFuture.failedFuture(new IllegalStateException("private database detail")),
            CompletableFuture.completedFuture(null));
    AtomicInteger moderation = new AtomicInteger(), telemetryRequests = new AtomicInteger();
    SyncEngine engine =
        engine(
            (method, uri, headers, body) -> {
              if (uri.getPath().endsWith("/players")) telemetryRequests.incrementAndGet();
              else moderation.incrementAndGet();
              return CompletableFuture.completedFuture(success(uri, headers, body));
            },
            ApiRequestGate.unpaced(clock));
    assertEquals("PLAYER_STORAGE_UNAVAILABLE", engine.runCycle(false).category());
    assertEquals(1, moderation.get());
    assertEquals(0, telemetryRequests.get());
    assertFalse(engine.status().contains("private"));
    clock.now = clock.now.plusSeconds(10);
    assertTrue(engine.cycle(false));
    assertEquals(2, moderation.get());
    assertEquals(1, telemetryRequests.get());
    verify(repository, times(2)).initialize();
  }
}
