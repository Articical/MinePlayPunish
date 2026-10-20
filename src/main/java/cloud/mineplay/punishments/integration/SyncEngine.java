package cloud.mineplay.punishments.integration;

import cloud.mineplay.punishments.appeals.AppealLinks;
import cloud.mineplay.punishments.punishment.Models.*;
import cloud.mineplay.punishments.punishment.PunishmentService;
import cloud.mineplay.punishments.storage.Database;
import com.google.gson.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/** Runs only on a dedicated sync worker. It holds no Bukkit objects. */
public final class SyncEngine implements AutoCloseable {
  private final Database database;
  private final PunishmentService service;
  private final SyncSettings settings;
  private final ApiTransport transport;
  private final Supplier<String> credentials;
  private final Runnable enforcementChanged;
  private final Clock clock;
  private final RemoteActionProcessor inbox;
  private final AppealLinks appeals;
  private boolean reconciled;
  private String reconciliationSnapshot;
  private int failures;
  private Instant nextAttempt = Instant.MIN;
  private volatile String status = "Not started";
  private final AtomicBoolean closed = new AtomicBoolean();
  private final AtomicReference<CompletableFuture<ApiTransport.Response>> pendingRequest =
      new AtomicReference<>();

  public SyncEngine(
      Database database,
      PunishmentService service,
      SyncSettings settings,
      ApiTransport transport,
      Supplier<String> credentials,
      Runnable enforcementChanged,
      BooleanSupplier serviceReady,
      Clock clock) {
    this(
        database,
        service,
        settings,
        transport,
        credentials,
        enforcementChanged,
        serviceReady,
        clock,
        Set.of("WOODEN_PICKAXE", "STONE_PICKAXE"),
        "WOODEN_PICKAXE");
  }

  public SyncEngine(
      Database database,
      PunishmentService service,
      SyncSettings settings,
      ApiTransport transport,
      Supplier<String> credentials,
      Runnable enforcementChanged,
      BooleanSupplier serviceReady,
      Clock clock,
      Set<String> allowedTools,
      String defaultTool) {
    this.database = database;
    this.service = service;
    this.settings = settings;
    this.transport = transport;
    this.credentials = credentials;
    this.enforcementChanged = enforcementChanged;
    this.clock = clock;
    appeals = new AppealLinks(settings.appealBase());
    inbox =
        new RemoteActionProcessor(
            database,
            service,
            settings.serverId(),
            clock,
            appeals,
            serviceReady,
            settings.kickExpirySeconds(),
            allowedTools,
            defaultTool);
  }

  public void initialize() {
    inbox.initialize().join();
    database
        .write(
            connection -> {
              try (Statement sql = connection.createStatement()) {
                sql.execute(
                    "CREATE TABLE IF NOT EXISTS appeal_requests (punishment_id TEXT PRIMARY KEY"
                        + " REFERENCES punishments(id), provisioning_id TEXT NOT NULL UNIQUE,"
                        + " created_at TEXT NOT NULL, state TEXT NOT NULL DEFAULT 'PENDING')");
              }
              return null;
            })
        .join();
    service.refresh().join();
    enforcementChanged.run();
    status =
        settings.enabled()
            ? "Awaiting startup reconciliation"
            : "Website disabled; local enforcement active";
  }

  public String status() {
    return status;
  }

  /** Returns whether a cycle succeeded; failures retain every pending durable record. */
  public boolean cycle(boolean manual) {
    if (closed.get()) return false;
    if (!settings.enabled()) return true;
    if (!manual && clock.instant().isBefore(nextAttempt)) return false;
    try {
      // Recover a failed cache publication before acknowledging any already committed inbox result.
      if (!service.healthy()) {
        service.refresh().join();
        enforcementChanged.run();
      }
      RuntimeException pendingFailure = null;
      try {
        fetchActions();
        acknowledge();
      } catch (RuntimeException failure) {
        pendingFailure = failure;
      }
      // Keep the outgoing lane usable when one incoming command needs reconciliation.
      // Publishing local revisions is often precisely what lets staff resolve a conflict.
      try {
        publishEvents();
        if (settings.appealsEnabled()) provisionAppeals();
      } catch (RuntimeException failure) {
        if (pendingFailure == null) pendingFailure = failure;
      }
      if (pendingFailure != null) throw pendingFailure;
      database
          .write(
              connection -> {
                try (PreparedStatement sql =
                    connection.prepareStatement(
                        "UPDATE sync_state SET last_success=? WHERE server_id=?")) {
                  sql.setString(1, clock.instant().toString());
                  sql.setString(2, settings.serverId());
                  sql.executeUpdate();
                }
                return null;
              })
          .join();
      failures = 0;
      nextAttempt = clock.instant().plusSeconds(settings.intervalSeconds());
      status =
          reconciled ? "Synchronized " + clock.instant() : "Startup reconciliation in progress";
      return true;
    } catch (RuntimeException failure) {
      if (closed.get()) {
        status = "Stopped";
        return false;
      }
      failures = Math.min(failures + 1, 31);
      Throwable cause = failure;
      while ((cause instanceof CompletionException || cause instanceof ExecutionException)
          && cause.getCause() != null) cause = cause.getCause();
      String category =
          cause instanceof ApiFailure api
              ? api.category
              : cause instanceof TimeoutException
                      || cause instanceof java.net.http.HttpTimeoutException
                  ? "TIMEOUT"
                  : cause instanceof IllegalArgumentException
                      ? "INVALID_RESPONSE"
                      : cause instanceof SQLException ? "DATABASE_FAILURE" : "UNAVAILABLE";
      long delay =
          category.equals("AUTHENTICATION") || category.equals("CREDENTIAL_MISSING")
              ? settings.maxBackoffSeconds()
              : settings.backoff(failures - 1);
      nextAttempt = clock.instant().plusSeconds(delay);
      status = category + "; retry after " + nextAttempt + "; local records retained";
      return false;
    }
  }

  private void fetchActions() {
    for (int page = 0; page < 4; page++) {
      String cursor =
          database
              .read(
                  connection -> {
                    try (PreparedStatement sql =
                        connection.prepareStatement(
                            "SELECT remote_cursor FROM sync_state WHERE server_id=?")) {
                      sql.setString(1, settings.serverId());
                      try (ResultSet rs = sql.executeQuery()) {
                        return rs.next() ? Objects.requireNonNullElse(rs.getString(1), "") : "";
                      }
                    }
                  })
              .join();
      boolean reconciliation = !reconciled;
      JsonObject response =
          request(
              "GET",
              (reconciliation ? "/reconciliation" : "/actions")
                  + "?serverId="
                  + encode(settings.serverId())
                  + "&cursor="
                  + encode(cursor)
                  + "&limit="
                  + settings.batchSize(),
              null);
      JsonElement items = response.get("actions");
      if (items == null
          || !items.isJsonArray()
          || items.getAsJsonArray().size() > settings.batchSize())
        throw ApiProtocol.invalid("actions");
      String nextCursor = ApiProtocol.string(response, "cursor", 512);
      boolean more = ApiProtocol.bool(response, "hasMore");
      if (more && nextCursor.equals(cursor)) throw ApiProtocol.invalid("nonadvancing cursor");
      String snapshot =
          reconciliation ? ApiProtocol.string(response, "snapshotRevision", 128) : null;
      if (reconciliation) {
        if (reconciliationSnapshot != null && !reconciliationSnapshot.equals(snapshot))
          throw ApiProtocol.invalid("changing reconciliation snapshot");
        reconciliationSnapshot = snapshot;
      }
      // The high-water snapshot is verified only when every page through it is committed.
      inbox
          .accept(items.getAsJsonArray(), nextCursor, reconciliation && !more ? snapshot : null)
          .join();
      enforcementChanged.run();
      if (!more) {
        if (reconciliation) reconciled = true;
        break;
      }
    }
  }

  private void acknowledge() {
    JsonArray items =
        database
            .read(
                connection -> {
                  JsonArray result = new JsonArray();
                  try (PreparedStatement sql =
                      connection.prepareStatement(
                          "SELECT result_json FROM remote_acknowledgements WHERE"
                              + " delivery_state='PENDING' ORDER BY rowid LIMIT ?")) {
                    sql.setInt(1, settings.batchSize());
                    try (ResultSet rs = sql.executeQuery()) {
                      while (rs.next()) result.add(JsonParser.parseString(rs.getString(1)));
                    }
                  }
                  return result;
                })
            .join();
    if (items.isEmpty()) return;
    JsonObject payload = new JsonObject();
    payload.add("acknowledgements", items);
    JsonObject response = request("POST", "/actions/acknowledgements", payload);
    Set<String> sent = new HashSet<>();
    items.forEach(item -> sent.add(item.getAsJsonObject().get("actionId").getAsString()));
    Set<String> accepted = acceptedIds(response, "acceptedActionIds", sent);
    database
        .write(
            connection -> {
              try (PreparedStatement sql =
                  connection.prepareStatement(
                      "UPDATE remote_acknowledgements SET delivery_state='DELIVERED' WHERE"
                          + " action_id=?")) {
                for (String id : accepted) {
                  sql.setString(1, id);
                  sql.addBatch();
                }
                sql.executeBatch();
              }
              return null;
            })
        .join();
  }

  private record Outbox(String id, JsonObject payload, int retries) {}

  private void publishEvents() {
    List<Outbox> pending =
        database
            .read(
                connection -> {
                  List<Outbox> rows = new ArrayList<>();
                  try (PreparedStatement sql =
                      connection.prepareStatement(
                          "SELECT event_id,payload,retry_count,next_attempt FROM sync_outbox WHERE"
                              + " delivery_state='PENDING' ORDER BY created_at,event_id")) {
                    try (ResultSet rs = sql.executeQuery()) {
                      while (rs.next() && rows.size() < settings.batchSize()) {
                        String due = rs.getString(4);
                        if (due == null || !Instant.parse(due).isAfter(clock.instant()))
                          rows.add(
                              new Outbox(
                                  rs.getString(1),
                                  JsonParser.parseString(rs.getString(2)).getAsJsonObject(),
                                  rs.getInt(3)));
                      }
                    }
                  }
                  return rows;
                })
            .join();
    if (pending.isEmpty()) return;
    Set<String> accepted = Set.of();
    try {
      JsonObject payload = new JsonObject();
      JsonArray events = new JsonArray();
      pending.forEach(row -> events.add(row.payload()));
      payload.add("events", events);
      JsonObject response = request("POST", "/events", payload);
      accepted =
          acceptedIds(
              response,
              "acceptedEventIds",
              pending.stream().map(Outbox::id).collect(java.util.stream.Collectors.toSet()));
    } finally {
      Set<String> delivered = accepted;
      database
          .write(
              connection -> {
                try (PreparedStatement sql =
                    connection.prepareStatement(
                        "UPDATE sync_outbox SET"
                            + " delivery_state=?,retry_count=?,last_attempt=?,next_attempt=? WHERE"
                            + " event_id=?")) {
                  for (Outbox row : pending) {
                    sql.setString(1, delivered.contains(row.id()) ? "DELIVERED" : "PENDING");
                    sql.setInt(
                        2,
                        row.retries() == Integer.MAX_VALUE ? Integer.MAX_VALUE : row.retries() + 1);
                    sql.setString(3, clock.instant().toString());
                    sql.setString(
                        4, clock.instant().plusSeconds(settings.backoff(row.retries())).toString());
                    sql.setString(5, row.id());
                    sql.addBatch();
                  }
                  sql.executeBatch();
                }
                return null;
              })
          .join();
    }
  }

  private record Provision(
      UUID punishmentId, UUID provisioningId, UUID targetId, String targetName, long revision) {}

  private void provisionAppeals() {
    List<Provision> pending =
        database
            .write(
                connection -> {
                  List<Provision> rows = new ArrayList<>();
                  try (PreparedStatement select =
                          connection.prepareStatement(
                              "SELECT id FROM punishments WHERE status='ACTIVE' AND type IN"
                                  + " ('BAN','MUTE','WARNING','COMMUNITY_SERVICE') AND appeal_url"
                                  + " IS NULL");
                      PreparedStatement insert =
                          connection.prepareStatement(
                              "INSERT OR IGNORE INTO"
                                  + " appeal_requests(punishment_id,provisioning_id,created_at)"
                                  + " VALUES(?,?,?)")) {
                    try (ResultSet rs = select.executeQuery()) {
                      while (rs.next()) {
                        insert.setString(1, rs.getString(1));
                        insert.setString(2, UUID.randomUUID().toString());
                        insert.setString(3, clock.instant().toString());
                        insert.addBatch();
                      }
                    }
                    insert.executeBatch();
                  }
                  try (PreparedStatement sql =
                      connection.prepareStatement(
                          "SELECT p.id,a.provisioning_id,p.target_id,p.target_name,p.revision FROM"
                              + " appeal_requests a JOIN punishments p ON p.id=a.punishment_id"
                              + " WHERE a.state='PENDING' AND p.status='ACTIVE' AND p.appeal_url IS"
                              + " NULL ORDER BY a.created_at LIMIT ?")) {
                    sql.setInt(1, Math.min(settings.batchSize(), 10));
                    try (ResultSet rs = sql.executeQuery()) {
                      while (rs.next())
                        rows.add(
                            new Provision(
                                UUID.fromString(rs.getString(1)),
                                UUID.fromString(rs.getString(2)),
                                UUID.fromString(rs.getString(3)),
                                rs.getString(4),
                                rs.getLong(5)));
                    }
                  }
                  return rows;
                })
            .join();
    for (Provision provision : pending) {
      JsonObject payload = new JsonObject();
      payload.addProperty("provisioningId", provision.provisioningId().toString());
      payload.addProperty("punishmentId", provision.punishmentId().toString());
      payload.addProperty("targetPlayerId", provision.targetId().toString());
      JsonObject response = request("POST", "/appeals/provision", payload);
      if (!ApiProtocol.uuid(response, "provisioningId").equals(provision.provisioningId())
          || !ApiProtocol.uuid(response, "punishmentId").equals(provision.punishmentId()))
        throw ApiProtocol.invalid("appeal identity");
      String url = appeals.validate(ApiProtocol.string(response, "appealUrl", 2048));
      database
          .write(
              connection -> {
                long revision;
                try (PreparedStatement sql =
                    connection.prepareStatement(
                        "SELECT p.revision,p.appeal_url,p.status,a.provisioning_id FROM punishments"
                            + " p JOIN appeal_requests a ON a.punishment_id=p.id WHERE p.id=?")) {
                  sql.setString(1, provision.punishmentId().toString());
                  try (ResultSet rs = sql.executeQuery()) {
                    if (!rs.next()) return null;
                    // A local rotation while HTTP was in flight supersedes this request.
                    if (!provision.provisioningId().toString().equals(rs.getString(4))) return null;
                    if (rs.getString(2) != null || !rs.getString(3).equals("ACTIVE")) {
                      finishProvision(connection, provision.punishmentId());
                      return null;
                    }
                    revision = rs.getLong(1);
                  }
                }
                JsonObject actionPayload = new JsonObject();
                actionPayload.addProperty("appealUrl", url);
                Result result =
                    service.apply(
                        connection,
                        new Action(
                            provision.provisioningId(),
                            ActionType.ROTATE_APPEAL_TOKEN,
                            provision.targetId(),
                            provision.targetName(),
                            provision.punishmentId(),
                            revision,
                            "website:appeal-provisioning",
                            Source.WEBSITE,
                            clock.instant(),
                            null,
                            actionPayload));
                if (!result.applied())
                  throw new IllegalStateException("Appeal provisioning commit rejected");
                finishProvision(connection, provision.punishmentId());
                return null;
              })
          .join();
      service.refresh().join();
      enforcementChanged.run();
    }
  }

  private static void finishProvision(Connection connection, UUID id) throws SQLException {
    try (PreparedStatement sql =
        connection.prepareStatement(
            "UPDATE appeal_requests SET state='COMPLETE' WHERE punishment_id=?")) {
      sql.setString(1, id.toString());
      sql.executeUpdate();
    }
  }

  private JsonObject request(String method, String suffix, JsonObject payload) {
    if (closed.get()) throw new ApiFailure("STOPPED");
    String credential =
        credentials.get(); // Re-read each request: provider may supply rotated credentials.
    if (credential == null || credential.isBlank()) throw new ApiFailure("CREDENTIAL_MISSING");
    if (credential.length() > 4096 || credential.chars().anyMatch(c -> c <= 32 || c >= 127))
      throw new ApiFailure("CREDENTIAL_MISSING");
    String requestId = UUID.randomUUID().toString();
    String root = settings.apiRoot().toASCIIString().replaceAll("/+$", "");
    if (payload != null) {
      payload.addProperty("schemaVersion", 1);
      payload.addProperty("requestId", requestId);
      payload.addProperty("serverId", settings.serverId());
    }
    CompletableFuture<ApiTransport.Response> future =
        transport
            .send(
                method,
                URI.create(root + suffix),
                Map.of(
                    "Authorization",
                    "Bearer " + credential,
                    "Accept",
                    "application/json",
                    "Content-Type",
                    "application/json",
                    "X-Request-Id",
                    requestId,
                    "X-Server-Id",
                    settings.serverId()),
                payload == null ? null : payload.toString())
            .orTimeout(settings.requestTimeout().toMillis(), TimeUnit.MILLISECONDS);
    pendingRequest.set(future);
    if (closed.get()) future.cancel(true);
    ApiTransport.Response response;
    try {
      response = future.join();
    } finally {
      pendingRequest.compareAndSet(future, null);
    }
    if (closed.get()) throw new ApiFailure("STOPPED");
    if (response.status() == 401 || response.status() == 403)
      throw new ApiFailure("AUTHENTICATION");
    if (response.status() == 408 || response.status() == 429 || response.status() >= 500)
      throw new ApiFailure("HTTP_TRANSIENT");
    if (response.status() != 200) throw new ApiFailure("HTTP_CONTRACT");
    return ApiProtocol.response(response.body(), requestId, settings.serverId());
  }

  private static Set<String> acceptedIds(JsonObject response, String field, Set<String> expected) {
    JsonElement element = response.get(field);
    if (element == null || !element.isJsonArray()) throw ApiProtocol.invalid(field);
    Set<String> result = new HashSet<>();
    for (JsonElement value : element.getAsJsonArray()) {
      if (!value.isJsonPrimitive()
          || !value.getAsJsonPrimitive().isString()
          || !expected.contains(value.getAsString())
          || !result.add(value.getAsString())) throw ApiProtocol.invalid(field);
    }
    return result;
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static final class ApiFailure extends RuntimeException {
    private final String category;

    ApiFailure(String category) {
      super(category);
      this.category = category;
    }
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) return;
    CompletableFuture<ApiTransport.Response> request = pendingRequest.getAndSet(null);
    if (request != null) request.cancel(true);
    transport.close();
    status = "Stopped";
  }
}
