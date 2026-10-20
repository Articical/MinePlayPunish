package cloud.mineplay.punishments.integration;

import cloud.mineplay.punishments.appeals.AppealLinks;
import cloud.mineplay.punishments.punishment.Models.*;
import cloud.mineplay.punishments.punishment.PunishmentService;
import cloud.mineplay.punishments.storage.Database;
import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

/** Inbox, command outcome, acknowledgement and cursor commit in the same SQLite transaction. */
public final class RemoteActionProcessor {
  private final Database database;
  private final PunishmentService service;
  private final String serverId;
  private final Clock clock;
  private final AppealLinks appeals;
  private final BooleanSupplier serviceReady;
  private final int kickExpirySeconds;
  private final Set<String> allowedTools;
  private final String defaultTool;

  public RemoteActionProcessor(
      Database database,
      PunishmentService service,
      String serverId,
      Clock clock,
      AppealLinks appeals,
      BooleanSupplier serviceReady,
      int kickExpirySeconds) {
    this(
        database,
        service,
        serverId,
        clock,
        appeals,
        serviceReady,
        kickExpirySeconds,
        Set.of("WOODEN_PICKAXE", "STONE_PICKAXE"),
        "WOODEN_PICKAXE");
  }

  public RemoteActionProcessor(
      Database database,
      PunishmentService service,
      String serverId,
      Clock clock,
      AppealLinks appeals,
      BooleanSupplier serviceReady,
      int kickExpirySeconds,
      Set<String> allowedTools,
      String defaultTool) {
    this.database = database;
    this.service = service;
    this.serverId = serverId;
    this.clock = clock;
    this.appeals = appeals;
    this.serviceReady = serviceReady;
    this.kickExpirySeconds = kickExpirySeconds;
    this.allowedTools = Set.copyOf(allowedTools);
    this.defaultTool = defaultTool;
    if (this.allowedTools.isEmpty()
        || !Set.of("WOODEN_PICKAXE", "STONE_PICKAXE").containsAll(this.allowedTools)
        || !this.allowedTools.contains(defaultTool))
      throw new IllegalArgumentException("Invalid remote service tool configuration");
  }

  public CompletableFuture<Void> initialize() {
    return database.write(
        connection -> {
          try (Statement sql = connection.createStatement()) {
            sql.execute(
                "CREATE TABLE IF NOT EXISTS remote_acknowledgements (action_id TEXT PRIMARY KEY"
                    + " REFERENCES remote_inbox(action_id), result_json TEXT NOT NULL,"
                    + " delivery_state TEXT NOT NULL DEFAULT 'PENDING')");
          }
          try (PreparedStatement sql =
              connection.prepareStatement(
                  "INSERT OR IGNORE INTO sync_state(server_id, remote_cursor) VALUES (?, '')")) {
            sql.setString(1, serverId);
            sql.executeUpdate();
          }
          return null;
        });
  }

  public CompletableFuture<List<JsonObject>> accept(
      JsonArray actions, String cursor, String snapshotRevision) {
    if (actions.size() > 1000
        || cursor.length() > 512
        || snapshotRevision != null && snapshotRevision.length() > 128)
      return CompletableFuture.failedFuture(ApiProtocol.invalid("batch bounds"));
    // An unaddressable action cannot be durably acknowledged. Reject the whole page without
    // advancing.
    try {
      for (JsonElement item : actions)
        ApiProtocol.uuid(ApiProtocol.object(item, "action"), "actionId");
    } catch (RuntimeException e) {
      return CompletableFuture.failedFuture(e);
    }
    return database
        .write(
            connection -> {
              List<JsonObject> outcomes = new ArrayList<>();
              for (JsonElement item : actions)
                outcomes.add(acceptOne(connection, item.getAsJsonObject()));
              try (PreparedStatement sql =
                  connection.prepareStatement(
                      "UPDATE sync_state SET remote_cursor=?,"
                          + " snapshot_revision=COALESCE(?,snapshot_revision) WHERE server_id=?")) {
                sql.setString(1, cursor);
                sql.setString(2, snapshotRevision);
                sql.setString(3, serverId);
                sql.executeUpdate();
              }
              return List.copyOf(outcomes);
            })
        .thenCompose(outcomes -> service.refresh().thenApply(ignored -> outcomes));
  }

  private JsonObject acceptOne(Connection connection, JsonObject wire) throws Exception {
    UUID id = ApiProtocol.uuid(wire, "actionId");
    String hash =
        HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(ApiProtocol.canonical(wire).getBytes(StandardCharsets.UTF_8)));
    try (PreparedStatement sql =
        connection.prepareStatement(
            "SELECT payload_hash, result_json FROM remote_inbox WHERE action_id=?")) {
      sql.setString(1, id.toString());
      try (ResultSet rs = sql.executeQuery()) {
        if (rs.next()) {
          if (!hash.equals(rs.getString(1))) {
            // A changed replay must never replace the acknowledged original result.
            throw ApiProtocol.invalid("replayed action payload");
          }
          JsonObject previous = JsonParser.parseString(rs.getString(2)).getAsJsonObject();
          queueAck(connection, id, previous);
          return previous;
        }
      }
    }
    JsonObject outcome;
    Long expected = null;
    Action action = null;
    try {
      action = decode(wire);
    } catch (IllegalArgumentException e) {
      // A rejected wire command is an outcome; exceptions from applying it must roll back.
    }
    if (action == null) {
      outcome = outcome(id, false, "INVALID_ACTION", null);
    } else {
      expected = action.expectedRevision();
      if (action.validUntil() != null && !action.validUntil().isAfter(clock.instant())) {
        outcome = outcome(id, false, "EXPIRED", null);
      } else if (action.type() == ActionType.CREATE_COMMUNITY_SERVICE
          && !serviceReady.getAsBoolean()) {
        outcome = outcome(id, false, "QUARRY_UNAVAILABLE", null);
      } else {
        // Database exceptions must escape and roll back both inbox and mutation.
        Result result = service.apply(connection, action);
        outcome = outcome(id, result.applied(), result.code(), result.punishment());
      }
    }
    try (PreparedStatement sql =
        connection.prepareStatement(
            "INSERT INTO"
                + " remote_inbox(action_id,source_revision,received_at,validation_status,execution_status,execution_at,result_json,payload_hash)"
                + " VALUES(?,?,?,?,?,?,?,?)")) {
      sql.setString(1, id.toString());
      if (expected == null) sql.setNull(2, Types.BIGINT);
      else sql.setLong(2, expected);
      sql.setString(3, clock.instant().toString());
      sql.setString(
          4, outcome.get("code").getAsString().equals("INVALID_ACTION") ? "REJECTED" : "VALID");
      sql.setString(5, outcome.get("applied").getAsBoolean() ? "APPLIED" : "REJECTED");
      sql.setString(6, clock.instant().toString());
      sql.setString(7, outcome.toString());
      sql.setString(8, hash);
      sql.executeUpdate();
    }
    queueAck(connection, id, outcome);
    return outcome;
  }

  private static JsonObject outcome(UUID id, boolean applied, String code, Punishment punishment) {
    JsonObject result = new JsonObject();
    result.addProperty("actionId", id.toString());
    result.addProperty("applied", applied);
    result.addProperty("code", code);
    if (punishment != null) {
      result.addProperty("punishmentId", punishment.id().toString());
      result.addProperty("revision", punishment.revision());
    }
    return result;
  }

  private static void queueAck(Connection connection, UUID id, JsonObject outcome)
      throws SQLException {
    try (PreparedStatement sql =
        connection.prepareStatement(
            "INSERT INTO remote_acknowledgements(action_id,result_json,delivery_state)"
                + " VALUES(?,?,'PENDING') ON CONFLICT(action_id) DO UPDATE SET"
                + " delivery_state='PENDING'")) {
      sql.setString(1, id.toString());
      sql.setString(2, outcome.toString());
      sql.executeUpdate();
    }
  }

  public Action decode(JsonObject wire) {
    ApiProtocol.only(
        wire,
        "actionId",
        "type",
        "target",
        "punishmentId",
        "expectedRevision",
        "staffId",
        "issuedAt",
        "validUntil",
        "payload");
    UUID id = ApiProtocol.uuid(wire, "actionId");
    ActionType type = ActionType.valueOf(ApiProtocol.string(wire, "type", 40));
    JsonObject target = ApiProtocol.object(wire.get("target"), "target");
    ApiProtocol.only(target, "uuid", "lastKnownName");
    UUID targetId = ApiProtocol.uuid(target, "uuid");
    String name =
        target.has("lastKnownName") && !target.get("lastKnownName").isJsonNull()
            ? ApiProtocol.string(target, "lastKnownName", 16)
            : null;
    if (name != null && !name.matches("[A-Za-z0-9_]{1,16}"))
      throw ApiProtocol.invalid("target name");
    UUID punishmentId = ApiProtocol.uuid(wire, "punishmentId");
    Long expected =
        wire.has("expectedRevision") && !wire.get("expectedRevision").isJsonNull()
            ? ApiProtocol.integer(wire, "expectedRevision", 0, Long.MAX_VALUE)
            : null;
    boolean create = type.name().startsWith("CREATE_");
    if (create && expected != null && expected != 0 || !create && expected == null)
      throw ApiProtocol.invalid("expected revision");
    String issuer = ApiProtocol.string(wire, "staffId", 128);
    Instant issued = ApiProtocol.instant(wire, "issuedAt");
    Instant validUntil = ApiProtocol.instant(wire, "validUntil");
    if (issued.isAfter(clock.instant().plusSeconds(60)) || !validUntil.isAfter(issued))
      throw ApiProtocol.invalid("action times");
    if (type == ActionType.CREATE_KICK && validUntil.isAfter(issued.plusSeconds(kickExpirySeconds)))
      validUntil = issued.plusSeconds(kickExpirySeconds);
    JsonObject payload = ApiProtocol.object(wire.get("payload"), "payload").deepCopy();
    switch (type) {
      case CREATE_BAN, CREATE_MUTE, CREATE_WARNING ->
          ApiProtocol.only(payload, "reason", "expiresAt", "appealUrl");
      case CREATE_KICK -> ApiProtocol.only(payload, "reason");
      case CREATE_COMMUNITY_SERVICE ->
          ApiProtocol.only(payload, "reason", "blocks", "tool", "appealUrl");
      case REVOKE_PUNISHMENT, RELEASE_SERVICE -> ApiProtocol.only(payload, "reason");
      case MODIFY_EXPIRATION -> ApiProtocol.only(payload, "reason", "expiresAt");
      case ADD_SERVICE_BLOCKS, SET_SERVICE_BLOCKS -> ApiProtocol.only(payload, "reason", "blocks");
      case ROTATE_APPEAL_TOKEN -> ApiProtocol.only(payload, "appealUrl");
    }
    if (payload.has("reason")) ApiProtocol.string(payload, "reason", 1000);
    if (create && !payload.has("reason")) throw ApiProtocol.invalid("reason");
    if (payload.has("expiresAt") && !payload.get("expiresAt").isJsonNull())
      ApiProtocol.instant(payload, "expiresAt");
    if (type == ActionType.MODIFY_EXPIRATION && !payload.has("expiresAt"))
      throw ApiProtocol.invalid("expiresAt");
    if (payload.has("blocks")) ApiProtocol.integer(payload, "blocks", 1, 1_000_000);
    if (type == ActionType.CREATE_COMMUNITY_SERVICE
        || type == ActionType.ADD_SERVICE_BLOCKS
        || type == ActionType.SET_SERVICE_BLOCKS)
      ApiProtocol.integer(payload, "blocks", 1, 1_000_000);
    if (type == ActionType.CREATE_COMMUNITY_SERVICE && !payload.has("tool"))
      payload.addProperty("tool", defaultTool);
    if (payload.has("tool") && !allowedTools.contains(ApiProtocol.string(payload, "tool", 20)))
      throw ApiProtocol.invalid("tool");
    if (payload.has("appealUrl"))
      payload.addProperty(
          "appealUrl", appeals.validate(ApiProtocol.string(payload, "appealUrl", 2048)));
    if (type == ActionType.ROTATE_APPEAL_TOKEN && !payload.has("appealUrl"))
      throw ApiProtocol.invalid("appealUrl");
    return new Action(
        id,
        type,
        targetId,
        name,
        punishmentId,
        expected,
        issuer,
        Source.WEBSITE,
        issued,
        validUntil,
        payload);
  }
}
