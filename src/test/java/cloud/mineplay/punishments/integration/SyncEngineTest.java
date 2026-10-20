package cloud.mineplay.punishments.integration;

import static org.junit.jupiter.api.Assertions.*;

import cloud.mineplay.punishments.punishment.Models.*;
import cloud.mineplay.punishments.punishment.PunishmentService;
import cloud.mineplay.punishments.storage.Database;
import com.google.gson.*;
import java.net.URI;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class SyncEngineTest {
  @TempDir Path temp;
  Database db;
  PunishmentService service;
  MutableClock clock;
  final List<SyncEngine> engines = new ArrayList<>();

  @BeforeEach
  void setup() throws Exception {
    db = new Database(temp.resolve("state.db"));
    db.initialize();
    clock = new MutableClock();
    service = new PunishmentService(db, "test-server", clock);
    service.refresh().join();
  }

  @AfterEach
  void close() throws Exception {
    engines.forEach(SyncEngine::close);
    db.close();
  }

  SyncSettings settings(boolean appeals) {
    return new SyncSettings(
        true,
        "test-server",
        URI.create("https://www.mineplay.co.uk/api/moderation/server"),
        "TEST_TOKEN",
        Duration.ofSeconds(1),
        Duration.ofMillis(100),
        5,
        10,
        300,
        100,
        30,
        appeals,
        "https://www.mineplay.co.uk/appeal/");
  }

  SyncEngine engine(
      ApiTransport transport, java.util.function.Supplier<String> credentials, boolean appeals) {
    SyncEngine result =
        new SyncEngine(
            db, service, settings(appeals), transport, credentials, () -> {}, () -> true, clock);
    result.initialize();
    engines.add(result);
    return result;
  }

  UUID ban() {
    UUID id = UUID.randomUUID();
    JsonObject payload = new JsonObject();
    payload.addProperty("reason", "Test ban");
    assertTrue(
        service
            .apply(
                new Action(
                    UUID.randomUUID(),
                    ActionType.CREATE_BAN,
                    UUID.randomUUID(),
                    "TestPlayer",
                    id,
                    null,
                    "console",
                    Source.CONSOLE,
                    clock.instant(),
                    null,
                    payload))
            .join()
            .applied());
    return id;
  }

  long pending() {
    return db.read(
            c -> {
              try (var sql = c.createStatement();
                  var rs =
                      sql.executeQuery(
                          "SELECT COUNT(*) FROM sync_outbox WHERE delivery_state='PENDING'")) {
                return rs.getLong(1);
              }
            })
        .join();
  }

  @Test
  void offlineWebsiteRetainsLocalEnforcementAndQueuedEvents() {
    UUID id = ban();
    SyncEngine engine =
        engine(
            (method, uri, headers, body) ->
                CompletableFuture.failedFuture(new java.io.IOException("offline")),
            () -> "secret",
            false);
    assertFalse(engine.cycle(true));
    assertEquals(Status.ACTIVE, service.find(id).orElseThrow().status());
    assertEquals(1, pending());
  }

  @Test
  void invalidAuthenticationNeverAppliesRemoteActions() {
    UUID id = ban();
    SyncEngine engine =
        engine(
            (method, uri, headers, body) ->
                CompletableFuture.completedFuture(
                    new ApiTransport.Response(401, "sensitive body must not be logged")),
            () -> "secret",
            false);
    assertFalse(engine.cycle(true));
    assertTrue(engine.status().startsWith("AUTHENTICATION"));
    assertFalse(engine.status().contains("sensitive"));
    assertEquals(Status.ACTIVE, service.find(id).orElseThrow().status());
  }

  @Test
  void requestTimeoutLeavesEventsPending() {
    ban();
    SyncEngine engine =
        engine((method, uri, headers, body) -> new CompletableFuture<>(), () -> "secret", false);
    assertTimeoutPreemptively(Duration.ofSeconds(3), () -> assertFalse(engine.cycle(true)));
    assertTrue(engine.status().startsWith("TIMEOUT"));
    assertEquals(1, pending());
  }

  @Test
  void missingCredentialSendsNoRequest() {
    AtomicInteger requests = new AtomicInteger();
    SyncEngine engine =
        engine(
            (method, uri, headers, body) -> {
              requests.incrementAndGet();
              return new CompletableFuture<>();
            },
            () -> null,
            false);
    assertFalse(engine.cycle(true));
    assertEquals(0, requests.get());
    assertTrue(engine.status().startsWith("CREDENTIAL_MISSING"));
  }

  @Test
  void queueSurvivesFailedDeliveryAndNewEngine() {
    ban();
    FakeTransport fake = new FakeTransport();
    fake.failEvents = true;
    SyncEngine first = engine(fake, () -> "secret", false);
    assertFalse(first.cycle(true));
    assertEquals(1, pending());
    clock.now = clock.now.plusSeconds(20);
    fake.failEvents = false;
    SyncEngine restarted = engine(fake, () -> "secret", false);
    assertTrue(restarted.cycle(true));
    assertEquals(0, pending());
    assertEquals(2, fake.eventDeliveries);
    assertTrue(fake.paths.stream().filter(path -> path.endsWith("/reconciliation")).count() >= 2);
  }

  @Test
  void credentialsAreReadAgainForEveryRequest() {
    ban();
    FakeTransport fake = new FakeTransport();
    AtomicInteger generation = new AtomicInteger();
    SyncEngine engine = engine(fake, () -> "credential-" + generation.incrementAndGet(), false);
    assertTrue(engine.cycle(true));
    assertTrue(generation.get() >= 2);
    assertEquals(fake.authorizations.size(), new HashSet<>(fake.authorizations).size());
  }

  @Test
  void pendingAcknowledgementResendsAfterLostResponseWithoutReissuingBan() {
    FakeTransport fake = new FakeTransport();
    fake.remote.add(
        RemoteActionProcessorTest.action("CREATE_BAN", UUID.randomUUID(), UUID.randomUUID(), null));
    fake.failAck = true;
    SyncEngine first = engine(fake, () -> "secret", false);
    assertFalse(first.cycle(true));
    assertEquals(
        1,
        db.read(
                c -> {
                  try (var s = c.createStatement();
                      var r = s.executeQuery("SELECT COUNT(*) FROM punishments")) {
                    return r.getInt(1);
                  }
                })
            .join());
    fake.remote = new JsonArray();
    fake.failAck = false;
    SyncEngine restarted = engine(fake, () -> "secret", false);
    assertTrue(restarted.cycle(true));
    assertEquals(2, fake.ackDeliveries);
  }

  @Test
  void appealProvisioningIsBackendRegisteredDurableAndPrivate() {
    UUID id = ban();
    FakeTransport fake = new FakeTransport();
    SyncEngine engine = engine(fake, () -> "secret", true);
    assertNull(service.find(id).orElseThrow().appealUrl());
    assertTrue(engine.cycle(true));
    String appeal = service.find(id).orElseThrow().appealUrl();
    assertEquals(fake.appealUrl, appeal);
    db.read(
            c -> {
              try (var s = c.createStatement();
                  var r = s.executeQuery("SELECT payload FROM sync_outbox")) {
                while (r.next()) assertFalse(r.getString(1).contains(appeal));
              }
              return null;
            })
        .join();
    assertTrue(engine.cycle(true));
    assertEquals(1, fake.provisions);
  }

  @Test
  void boundedBackoffNeverOverflows() {
    assertEquals(300, settings(false).backoff(Integer.MAX_VALUE));
    assertEquals(10, settings(false).backoff(0));
  }

  @Test
  void malformedIncomingPageDoesNotBlockPublishingLocalDecisionEvidence() {
    ban();
    FakeTransport fake = new FakeTransport();
    JsonObject malformed =
        RemoteActionProcessorTest.action("CREATE_BAN", UUID.randomUUID(), UUID.randomUUID(), null);
    malformed.addProperty("actionId", "invalid");
    fake.remote.add(malformed);
    SyncEngine engine = engine(fake, () -> "secret", false);
    assertFalse(engine.cycle(true));
    assertEquals(0, pending());
    assertEquals(1, fake.eventDeliveries);
  }

  @Test
  void staleProvisionResponseCannotUndoConcurrentRotation() {
    UUID id = ban();
    FakeTransport fake = new FakeTransport();
    AtomicBoolean rotated = new AtomicBoolean();
    SyncEngine engine =
        engine(
            (method, uri, headers, body) -> {
              if (uri.getPath().endsWith("/appeals/provision")
                  && rotated.compareAndSet(false, true)) {
                db.write(
                        c -> {
                          try (var s =
                              c.prepareStatement(
                                  "UPDATE appeal_requests SET provisioning_id=? WHERE"
                                      + " punishment_id=?")) {
                            s.setString(1, UUID.randomUUID().toString());
                            s.setString(2, id.toString());
                            s.executeUpdate();
                          }
                          return null;
                        })
                    .join();
              }
              return fake.send(method, uri, headers, body);
            },
            () -> "secret",
            true);
    assertTrue(engine.cycle(true));
    assertNull(service.find(id).orElseThrow().appealUrl());
    assertTrue(engine.cycle(true));
    assertNotNull(service.find(id).orElseThrow().appealUrl());
  }

  @Test
  void closingCancelsAnOutstandingRequestBeforeItCanCommitRemoteActions() throws Exception {
    CountDownLatch requested = new CountDownLatch(1);
    CompletableFuture<ApiTransport.Response> blocked = new CompletableFuture<>();
    SyncSettings config =
        new SyncSettings(
            true,
            "test-server",
            URI.create("https://www.mineplay.co.uk/api/moderation/server"),
            "TEST_TOKEN",
            Duration.ofSeconds(1),
            Duration.ofSeconds(30),
            5,
            10,
            300,
            100,
            30,
            false,
            "https://www.mineplay.co.uk/appeal/");
    SyncEngine engine =
        new SyncEngine(
            db,
            service,
            config,
            (method, uri, headers, body) -> {
              requested.countDown();
              return blocked;
            },
            () -> "secret",
            () -> {},
            () -> true,
            clock);
    engine.initialize();
    engines.add(engine);
    try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
      Future<Boolean> cycle = executor.submit(() -> engine.cycle(true));
      assertTrue(requested.await(2, TimeUnit.SECONDS));
      engine.close();
      assertFalse(cycle.get(2, TimeUnit.SECONDS));
      assertTrue(blocked.isCancelled());
      assertFalse(engine.cycle(true));
    }
  }

  @Test
  void snapshotIsVerifiedOnlyAfterAllReconciliationPagesCommit() {
    AtomicInteger pages = new AtomicInteger();
    SyncEngine engine =
        engine(
            (method, uri, headers, body) -> {
              int page = pages.incrementAndGet();
              JsonObject response = new JsonObject();
              response.addProperty("schemaVersion", 1);
              response.addProperty("requestId", headers.get("X-Request-Id"));
              response.addProperty("serverId", "test-server");
              response.add("actions", new JsonArray());
              response.addProperty("cursor", "cursor-" + page);
              response.addProperty("hasMore", page < 5);
              response.addProperty("snapshotRevision", "snapshot-5");
              return CompletableFuture.completedFuture(
                  new ApiTransport.Response(200, response.toString()));
            },
            () -> "secret",
            false);
    assertTrue(engine.cycle(true));
    assertEquals(4, pages.get());
    assertNull(
        db.read(
                c -> {
                  try (var s = c.createStatement();
                      var r = s.executeQuery("SELECT snapshot_revision FROM sync_state")) {
                    return r.getString(1);
                  }
                })
            .join());
    assertTrue(engine.cycle(true));
    assertEquals(
        "snapshot-5",
        db.read(
                c -> {
                  try (var s = c.createStatement();
                      var r = s.executeQuery("SELECT snapshot_revision FROM sync_state")) {
                    return r.getString(1);
                  }
                })
            .join());
  }

  static final class MutableClock extends Clock {
    Instant now = RemoteActionProcessorTest.NOW;

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

  static final class FakeTransport implements ApiTransport {
    boolean failEvents, failAck;
    int eventDeliveries, ackDeliveries, provisions;
    JsonArray remote = new JsonArray();
    final List<String> paths = new ArrayList<>(), authorizations = new ArrayList<>();
    final String appealUrl =
        "https://www.mineplay.co.uk/appeal/"
            + Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);

    @Override
    public CompletableFuture<Response> send(
        String method, URI uri, Map<String, String> headers, String body) {
      String path = uri.getPath();
      paths.add(path);
      authorizations.add(headers.get("Authorization"));
      JsonObject result = new JsonObject();
      result.addProperty("schemaVersion", 1);
      result.addProperty("requestId", headers.get("X-Request-Id"));
      result.addProperty("serverId", "test-server");
      JsonObject payload = body == null ? null : JsonParser.parseString(body).getAsJsonObject();
      if (path.endsWith("/reconciliation") || path.endsWith("/actions")) {
        result.add("actions", remote.deepCopy());
        result.addProperty("cursor", "cursor-1");
        result.addProperty("hasMore", false);
        result.addProperty("snapshotRevision", "snapshot-1");
      } else if (path.endsWith("/events")) {
        eventDeliveries++;
        if (failEvents) return CompletableFuture.completedFuture(new Response(503, ""));
        JsonArray ids = new JsonArray();
        payload
            .getAsJsonArray("events")
            .forEach(item -> ids.add(item.getAsJsonObject().get("eventId")));
        result.add("acceptedEventIds", ids);
      } else if (path.endsWith("/acknowledgements")) {
        ackDeliveries++;
        if (failAck) return CompletableFuture.completedFuture(new Response(503, ""));
        JsonArray ids = new JsonArray();
        payload
            .getAsJsonArray("acknowledgements")
            .forEach(item -> ids.add(item.getAsJsonObject().get("actionId")));
        result.add("acceptedActionIds", ids);
      } else if (path.endsWith("/appeals/provision")) {
        provisions++;
        result.add("punishmentId", payload.get("punishmentId"));
        result.add("provisioningId", payload.get("provisioningId"));
        result.addProperty("appealUrl", appealUrl);
      }
      return CompletableFuture.completedFuture(new Response(200, result.toString()));
    }
  }
}
