package cloud.mineplay.punishments.punishment;

import cloud.mineplay.punishments.punishment.Models.Action;
import cloud.mineplay.punishments.punishment.Models.ActionType;
import cloud.mineplay.punishments.punishment.Models.HistoryPage;
import cloud.mineplay.punishments.punishment.Models.Punishment;
import cloud.mineplay.punishments.punishment.Models.Result;
import cloud.mineplay.punishments.punishment.Models.Sentence;
import cloud.mineplay.punishments.punishment.Models.Source;
import cloud.mineplay.punishments.punishment.Models.Status;
import cloud.mineplay.punishments.punishment.Models.Type;
import cloud.mineplay.punishments.storage.Database;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/** Durable decisions and immutable enforcement indexes, independent of the Paper event thread. */
public final class PunishmentService {
  private record ActiveKey(UUID playerId, Type type) {}

  private record Cache(
      Map<UUID, Punishment> punishments,
      Map<ActiveKey, Punishment> active,
      Map<UUID, Sentence> sentences,
      Map<UUID, Instant> pendingKicks,
      long failureEpoch,
      long decisionEpoch) {}

  private record Limits(int minimumBlocks, int maximumBlocks, int maximumReasonLength) {}

  private static final Set<String> SERVICE_WORK_STATES =
      Set.of("ASSIGNED", "SNAPSHOTTED", "ACTIVE");
  private static final Set<String> PAYLOAD_KEYS =
      Set.of("reason", "expiresAt", "blocks", "tool", "appealUrl");
  private final Database database;
  private final String serverId;
  private final Clock clock;
  private final AtomicLong decisionEpoch = new AtomicLong();
  private volatile Cache cache;
  private volatile Limits limits = new Limits(10, 10_000, 1_000);
  private volatile URI appealBase = URI.create("https://www.mineplay.co.uk/appeal/");
  private volatile Map<Type, Boolean> enabledTypes = Map.of();

  public PunishmentService(Database database, String serverId, Clock clock) {
    this.database = Objects.requireNonNull(database, "database");
    if (serverId == null || !serverId.matches("[A-Za-z0-9._-]{1,100}")) {
      throw new IllegalArgumentException(
          "Server id must contain 1-100 letters, digits, dots, underscores or hyphens");
    }
    this.serverId = serverId;
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public void configureLimits(int minimumBlocks, int maximumBlocks, int maximumReasonLength) {
    if (minimumBlocks < 1
        || maximumBlocks < minimumBlocks
        || maximumBlocks > 1_000_000
        || maximumReasonLength < 1
        || maximumReasonLength > 10_000) {
      throw new IllegalArgumentException("Invalid punishment validation limits");
    }
    limits = new Limits(minimumBlocks, maximumBlocks, maximumReasonLength);
  }

  /** Only configuration may change this trust origin; moderation action payloads never may. */
  public void configureAppealBase(String value) {
    URI base;
    try {
      base = URI.create(Objects.requireNonNull(value, "appeal base"));
    } catch (IllegalArgumentException | NullPointerException failure) {
      throw new IllegalArgumentException("Invalid appeal URL base", failure);
    }
    if (!"https".equalsIgnoreCase(base.getScheme())
        || base.getHost() == null
        || base.getUserInfo() != null
        || base.getQuery() != null
        || base.getFragment() != null
        || base.getRawPath() == null
        || !base.getRawPath().matches("/[A-Za-z0-9/_-]*/")
        || !base.normalize().equals(base)
        || (base.getPort() != -1 && (base.getPort() < 1 || base.getPort() > 65535))) {
      throw new IllegalArgumentException(
          "Appeal URL base must be an absolute HTTPS directory URL without credentials, query or"
              + " fragment");
    }
    appealBase = base;
  }

  /** Disabling issuance leaves existing enforcement and safe revocation/recovery available. */
  public synchronized void configureTypeEnabled(Type type, boolean enabled) {
    EnumMap<Type, Boolean> copy = new EnumMap<>(Type.class);
    copy.putAll(enabledTypes);
    copy.put(Objects.requireNonNull(type, "type"), enabled);
    enabledTypes = Map.copyOf(copy);
  }

  public boolean healthy() {
    Cache current = cache;
    return current != null
        && database.available()
        && current.failureEpoch() == database.failureEpoch()
        && current.decisionEpoch() == decisionEpoch.get();
  }

  public CompletableFuture<Result> apply(Action action) {
    return database
        .write(connection -> apply(connection, action))
        .thenCompose(result -> refresh().thenApply(ignored -> result));
  }

  /** Caller must use Database.write and refresh after commit, before acknowledging the action. */
  public Result apply(Connection connection, Action action) throws SQLException {
    if (connection.getAutoCommit())
      throw new IllegalStateException("Punishment actions require a transaction");
    if (action == null || action.id() == null)
      return rejected("INVALID_ACTION", "An action UUID is required", null);
    String digest = actionHash(action);
    try (PreparedStatement query =
        connection.prepareStatement(
            "SELECT payload_hash,result_json FROM local_actions WHERE action_id=?")) {
      query.setString(1, action.id().toString());
      try (ResultSet rows = query.executeQuery()) {
        if (rows.next()) {
          if (!MessageDigest.isEqual(
              digest.getBytes(StandardCharsets.US_ASCII),
              rows.getString(1).getBytes(StandardCharsets.US_ASCII))) {
            return rejected(
                "REPLAY_MISMATCH",
                "This action UUID was already used with different content",
                null);
          }
          return readResult(JsonParser.parseString(rows.getString(2)).getAsJsonObject());
        }
      }
    }
    Result result;
    try {
      validateCommon(action);
      result = isCreation(action.type()) ? create(connection, action) : modify(connection, action);
    } catch (InvalidAction invalid) {
      result = rejected(invalid.code, invalid.getMessage(), null);
    }
    try (PreparedStatement insert =
        connection.prepareStatement(
            "INSERT INTO local_actions(action_id,payload_hash,result_json,completed_at) VALUES"
                + " (?,?,?,?)")) {
      insert.setString(1, action.id().toString());
      insert.setString(2, digest);
      insert.setString(3, resultJson(result).toString());
      insert.setString(4, clock.instant().toString());
      insert.executeUpdate();
    }
    return result;
  }

  private void validateCommon(Action action) {
    if (action.type() == null
        || action.targetId() == null
        || action.source() == null
        || action.issuedAt() == null) {
      throw invalid("INVALID_ACTION", "Type, target UUID, source and issue time are required");
    }
    if (action.issuer() == null
        || action.issuer().isBlank()
        || action.issuer().length() > 200
        || hasControls(action.issuer())) {
      throw invalid("INVALID_ISSUER", "A valid staff identity is required");
    }
    if (action.targetName() != null
        && (!action.targetName().matches("[A-Za-z0-9_]{1,16}")
            && !action.targetName().equals(action.targetId().toString()))) {
      throw invalid(
          "INVALID_TARGET", "Target name must be a Minecraft username or the target UUID");
    }
    Instant now = clock.instant();
    if (action.issuedAt().isAfter(now.plusSeconds(300)))
      throw invalid("INVALID_TIME", "Action issue time is too far in the future");
    if (action.validUntil() != null
        && (!action.validUntil().isAfter(action.issuedAt()) || !action.validUntil().isAfter(now))) {
      throw invalid("ACTION_EXPIRED", "This moderation instruction has expired");
    }
    JsonObject payload = action.payload();
    for (String key : payload.keySet())
      if (!PAYLOAD_KEYS.contains(key))
        throw invalid("INVALID_PAYLOAD", "Unknown action payload field: " + key);
    if (payload.has("reason")) reason(payload, null);
    if (payload.has("appealUrl") && !payload.get("appealUrl").isJsonNull())
      validateAppealUrl(string(payload, "appealUrl", true));
    Set<String> permitted =
        switch (action.type()) {
          case CREATE_BAN, CREATE_MUTE -> Set.of("reason", "expiresAt", "appealUrl");
          case CREATE_WARNING -> Set.of("reason", "expiresAt", "appealUrl");
          case CREATE_KICK -> Set.of("reason");
          case CREATE_COMMUNITY_SERVICE ->
              Set.of("reason", "expiresAt", "blocks", "tool", "appealUrl");
          case REVOKE_PUNISHMENT, RELEASE_SERVICE -> Set.of("reason");
          case MODIFY_EXPIRATION -> Set.of("reason", "expiresAt");
          case ADD_SERVICE_BLOCKS, SET_SERVICE_BLOCKS -> Set.of("reason", "blocks");
          case ROTATE_APPEAL_TOKEN -> Set.of("appealUrl");
        };
    for (String key : payload.keySet())
      if (!permitted.contains(key))
        throw invalid("INVALID_PAYLOAD", "Field is not valid for this action: " + key);
  }

  private Result create(Connection connection, Action action) throws SQLException {
    if (action.expectedRevision() != null && action.expectedRevision() != 0)
      return rejected("REVISION_CONFLICT", "Creation expects revision zero", null);
    Type type =
        switch (action.type()) {
          case CREATE_BAN -> Type.BAN;
          case CREATE_MUTE -> Type.MUTE;
          case CREATE_WARNING -> Type.WARNING;
          case CREATE_KICK -> Type.KICK;
          case CREATE_COMMUNITY_SERVICE -> Type.COMMUNITY_SERVICE;
          default -> throw new IllegalStateException("Not a create action");
        };
    if (!enabledTypes.getOrDefault(type, true))
      return rejected(
          "TYPE_DISABLED",
          "Issuing this punishment type is disabled in server configuration",
          null);
    JsonObject payload = action.payload();
    String reason = reason(payload, null);
    Instant expiresAt = expiration(payload);
    Instant now = clock.instant();
    if (expiresAt != null && !expiresAt.isAfter(now))
      throw invalid("INVALID_EXPIRATION", "New punishments must expire in the future");
    if (type == Type.KICK && action.source() == Source.WEBSITE && action.validUntil() == null) {
      throw invalid("INVALID_KICK_DEADLINE", "Remote kicks require a short delivery deadline");
    }
    if (type == Type.KICK
        && action.validUntil() != null
        && action.validUntil().isAfter(action.issuedAt().plusSeconds(300))) {
      throw invalid(
          "INVALID_KICK_DEADLINE", "Kick delivery deadline cannot exceed five minutes from issue");
    }
    int blocks = 0;
    String tool = null;
    if (type == Type.COMMUNITY_SERVICE) {
      blocks = blocks(payload);
      tool = payload.has("tool") ? string(payload, "tool", true) : "WOODEN_PICKAXE";
      if (!Set.of("WOODEN_PICKAXE", "STONE_PICKAXE").contains(tool))
        throw invalid("INVALID_TOOL", "Only wooden and stone pickaxes are supported");
      if (findSentence(connection, action.targetId()).isPresent()) {
        return rejected(
            "ACTIVE_SENTENCE_EXISTS",
            "The player already has work or inventory recovery pending",
            null);
      }
    }
    if (Set.of(Type.BAN, Type.MUTE, Type.COMMUNITY_SERVICE).contains(type)) {
      Optional<Punishment> active = activeFromDatabase(connection, action.targetId(), type);
      if (active.isPresent()) {
        Punishment current = active.get();
        if (isExpired(current, now)) expireOne(connection, current, now);
        else
          return rejected(
              "ACTIVE_PUNISHMENT_EXISTS",
              "The player already has an active " + type.name().toLowerCase(Locale.ROOT),
              current);
      }
    }
    UUID id = action.punishmentId() == null ? UUID.randomUUID() : action.punishmentId();
    Optional<Punishment> occupied = find(connection, id);
    if (occupied.isPresent())
      return rejected(
          "PUNISHMENT_ID_EXISTS", "The punishment UUID is already in use", occupied.get());
    String targetName =
        action.targetName() == null
            ? knownName(connection, action.targetId()).orElse(action.targetId().toString())
            : action.targetName();
    String appealUrl =
        payload.has("appealUrl") && !payload.get("appealUrl").isJsonNull()
            ? payload.get("appealUrl").getAsString()
            : null;
    Punishment punishment =
        new Punishment(
            id,
            newReference(connection),
            action.targetId(),
            targetName,
            type,
            reason,
            action.issuer(),
            action.source(),
            action.issuedAt(),
            expiresAt,
            type == Type.KICK ? Status.COMPLETED : Status.ACTIVE,
            1,
            appealUrl);
    try (PreparedStatement insert =
        connection.prepareStatement(
            "INSERT INTO"
                + " punishments(id,reference,target_id,target_name,type,reason,issuer,source,issued_at,expires_at,status,revision,appeal_url)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
      insert.setString(1, id.toString());
      insert.setString(2, punishment.reference());
      insert.setString(3, action.targetId().toString());
      insert.setString(4, targetName);
      insert.setString(5, type.name());
      insert.setString(6, reason);
      insert.setString(7, action.issuer());
      insert.setString(8, action.source().name());
      insert.setString(9, action.issuedAt().toString());
      insert.setString(10, instantText(expiresAt));
      insert.setString(11, punishment.status().name());
      insert.setLong(12, 1);
      insert.setString(13, appealUrl);
      insert.executeUpdate();
    }
    if (type == Type.COMMUNITY_SERVICE) {
      try (PreparedStatement insert =
          connection.prepareStatement(
              "INSERT INTO"
                  + " community_service(id,punishment_id,target_id,required,completed,tool,state)"
                  + " VALUES (?,?,?,?,0,?,'ASSIGNED')")) {
        insert.setString(1, UUID.randomUUID().toString());
        insert.setString(2, id.toString());
        insert.setString(3, action.targetId().toString());
        insert.setInt(4, blocks);
        insert.setString(5, tool);
        insert.executeUpdate();
      }
    }
    if (type == Type.WARNING || type == Type.KICK) {
      try (PreparedStatement insert =
          connection.prepareStatement(
              "INSERT INTO"
                  + " pending_notifications(player_id,punishment_id,notification_type,delivery_state,valid_until)"
                  + " VALUES (?,?,?,'PENDING',?)")) {
        insert.setString(1, action.targetId().toString());
        insert.setString(2, id.toString());
        insert.setString(3, type.name());
        insert.setString(
            4,
            type == Type.KICK
                ? instantText(
                    action.validUntil() == null ? now.plusSeconds(30) : action.validUntil())
                : null);
        insert.executeUpdate();
      }
    }
    if (type != Type.KICK && appealUrl == null) queueAppealRequest(connection, id, now);
    JsonObject event = publicPunishment(punishment);
    if (type == Type.COMMUNITY_SERVICE) {
      event.addProperty("requiredBlocks", blocks);
      event.addProperty("tool", tool);
      event.addProperty("state", "ASSIGNED");
    }
    if (type == Type.KICK) event.addProperty("deliveryState", "PENDING");
    emit(connection, punishment, "punishment.created", action.issuer(), event);
    return new Result(
        true,
        "APPLIED",
        type == Type.KICK ? "Kick queued for immediate delivery" : "Punishment created",
        punishment);
  }

  private Result modify(Connection connection, Action action) throws SQLException {
    if (action.punishmentId() == null)
      throw invalid("INVALID_ACTION", "A punishment UUID is required for modifications");
    Optional<Punishment> found = find(connection, action.punishmentId());
    if (found.isEmpty()) return rejected("NOT_FOUND", "Punishment does not exist", null);
    Punishment existing = found.get();
    if (!existing.targetId().equals(action.targetId()))
      return rejected("TARGET_MISMATCH", "The punishment belongs to another player", null);
    if (action.expectedRevision() == null || action.expectedRevision() != existing.revision()) {
      return rejected(
          "REVISION_CONFLICT",
          "Expected revision does not match the current local decision",
          existing);
    }
    if (existing.revision() == Long.MAX_VALUE)
      return rejected("REVISION_EXHAUSTED", "Punishment revision cannot be advanced", existing);
    JsonObject payload = action.payload();
    Instant now = clock.instant();
    String eventType;
    JsonObject event = new JsonObject();
    switch (action.type()) {
      case REVOKE_PUNISHMENT, RELEASE_SERVICE -> {
        if (action.type() == ActionType.RELEASE_SERVICE
            && existing.type() != Type.COMMUNITY_SERVICE)
          return rejected("WRONG_TYPE", "Only community service can be released", existing);
        Optional<Sentence> pendingSentence =
            existing.type() == Type.COMMUNITY_SERVICE
                ? findSentence(connection, existing.targetId())
                : Optional.empty();
        if (existing.status() != Status.ACTIVE && pendingSentence.isEmpty())
          return rejected("NOT_ACTIVE", "Punishment is already inactive", existing);
        String revocationReason =
            reason(
                payload,
                action.type() == ActionType.RELEASE_SERVICE
                    ? "Released by staff"
                    : "Revoked by staff");
        try (PreparedStatement update =
            connection.prepareStatement(
                "UPDATE punishments SET"
                    + " status='REVOKED',revoked_at=?,revoked_by=?,revocation_reason=?,revision=revision+1"
                    + " WHERE id=? AND revision=?")) {
          update.setString(1, now.toString());
          update.setString(2, action.issuer());
          update.setString(3, revocationReason);
          update.setString(4, existing.id().toString());
          update.setLong(5, existing.revision());
          requireChanged(update);
        }
        markWorkComplete(connection, existing);
        event.addProperty("revocationReason", revocationReason);
        eventType =
            action.type() == ActionType.RELEASE_SERVICE
                ? "community_service.released"
                : "punishment.revoked";
      }
      case MODIFY_EXPIRATION -> {
        if (existing.type() == Type.KICK)
          return rejected("WRONG_TYPE", "A historical kick has no ongoing expiration", existing);
        if (existing.status() != Status.ACTIVE)
          return rejected("NOT_ACTIVE", "Only active punishments can be modified", existing);
        if (!payload.has("expiresAt"))
          throw invalid(
              "INVALID_EXPIRATION", "expiresAt must be an ISO UTC timestamp or null for permanent");
        Instant expiresAt = expiration(payload);
        boolean expired = expiresAt != null && !expiresAt.isAfter(now);
        try (PreparedStatement update =
            connection.prepareStatement(
                "UPDATE punishments SET expires_at=?,status=?,revision=revision+1 WHERE id=? AND"
                    + " revision=?")) {
          update.setString(1, instantText(expiresAt));
          update.setString(2, expired ? "EXPIRED" : "ACTIVE");
          update.setString(3, existing.id().toString());
          update.setLong(4, existing.revision());
          requireChanged(update);
        }
        if (expired) markWorkComplete(connection, existing);
        putNullable(event, "expiresAt", instantText(expiresAt));
        if (payload.has("reason")) event.addProperty("changeReason", reason(payload, null));
        eventType = expired ? "punishment.expired" : "punishment.modified";
      }
      case ADD_SERVICE_BLOCKS, SET_SERVICE_BLOCKS -> {
        if (existing.type() != Type.COMMUNITY_SERVICE)
          return rejected("WRONG_TYPE", "This action requires community service", existing);
        Optional<Sentence> sentence = findSentence(connection, existing.targetId());
        if (existing.status() != Status.ACTIVE
            || sentence.isEmpty()
            || !sentence.get().punishmentId().equals(existing.id())
            || !SERVICE_WORK_STATES.contains(sentence.get().state()))
          return rejected(
              "NOT_ACTIVE",
              "Work cannot be changed while release or recovery is pending",
              existing);
        int change = blocks(payload);
        Sentence current = sentence.get();
        long required =
            action.type() == ActionType.ADD_SERVICE_BLOCKS
                ? (long) current.required() + change
                : change;
        if (required > limits.maximumBlocks())
          throw invalid("INVALID_BLOCKS", "Total work exceeds the configured maximum");
        String changeReason = reason(payload, null);
        String state = current.completed() >= required ? "WORK_COMPLETE" : current.state();
        try (PreparedStatement update =
            connection.prepareStatement(
                "UPDATE community_service SET required=?,state=? WHERE id=?")) {
          update.setInt(1, (int) required);
          update.setString(2, state);
          update.setString(3, current.id().toString());
          requireChanged(update);
        }
        advanceRevision(connection, existing);
        event.addProperty("previousRequiredBlocks", current.required());
        event.addProperty("requiredBlocks", required);
        event.addProperty("completedBlocks", current.completed());
        event.addProperty("changeReason", changeReason);
        event.addProperty("state", state);
        eventType = "community_service.modified";
      }
      case ROTATE_APPEAL_TOKEN -> {
        if (existing.type() == Type.KICK)
          return rejected(
              "WRONG_TYPE", "A historical kick does not have a provisioned appeal link", existing);
        // A null URL explicitly invalidates presentation until the backend provisions a
        // replacement.
        if (!payload.has("appealUrl"))
          throw invalid("INVALID_APPEAL_URL", "A provisioned appeal URL or null is required");
        String url =
            payload.get("appealUrl").isJsonNull() ? null : string(payload, "appealUrl", true);
        if (url != null) validateAppealUrl(url);
        try (PreparedStatement update =
            connection.prepareStatement(
                "UPDATE punishments SET appeal_url=?,revision=revision+1 WHERE id=? AND"
                    + " revision=?")) {
          update.setString(1, url);
          update.setString(2, existing.id().toString());
          update.setLong(3, existing.revision());
          requireChanged(update);
        }
        if (url == null) queueAppealRequest(connection, existing.id(), now);
        else
          try (PreparedStatement update =
              connection.prepareStatement(
                  "UPDATE appeal_requests SET state='COMPLETE' WHERE punishment_id=?")) {
            update.setString(1, existing.id().toString());
            update.executeUpdate();
          }
        event.addProperty("appealLinkProvisioned", url != null);
        eventType = "punishment.appeal_link_changed";
      }
      default -> throw invalid("INVALID_ACTION", "Unsupported modification");
    }
    Punishment updated = find(connection, existing.id()).orElseThrow();
    event.addProperty("status", updated.status().name());
    emit(connection, updated, eventType, action.issuer(), event);
    return new Result(true, "APPLIED", "Punishment updated", updated);
  }

  public CompletableFuture<Void> refresh() {
    return database.read(
        connection -> {
          Map<UUID, Punishment> all = new LinkedHashMap<>();
          Map<ActiveKey, Punishment> active = new HashMap<>();
          Map<UUID, Sentence> sentences = new HashMap<>();
          Map<UUID, Instant> kicks = new HashMap<>();
          try (var query = connection.createStatement();
              var rows = query.executeQuery("SELECT * FROM punishments")) {
            while (rows.next()) {
              Punishment punishment = readPunishment(rows);
              all.put(punishment.id(), punishment);
              if (punishment.status() == Status.ACTIVE && punishment.type() != Type.KICK)
                active.put(new ActiveKey(punishment.targetId(), punishment.type()), punishment);
            }
          }
          try (var query = connection.createStatement();
              var rows =
                  query.executeQuery("SELECT * FROM community_service WHERE state<>'COMPLETED'")) {
            while (rows.next()) {
              Sentence sentence = readSentence(rows);
              sentences.put(sentence.targetId(), sentence);
            }
          }
          try (var query = connection.createStatement();
              var rows =
                  query.executeQuery(
                      "SELECT punishment_id,valid_until FROM pending_notifications WHERE"
                          + " notification_type='KICK' AND delivery_state='PENDING'")) {
            while (rows.next())
              kicks.put(UUID.fromString(rows.getString(1)), Instant.parse(rows.getString(2)));
          }
          // Publish on the serialized worker. A delayed caller continuation cannot overwrite a
          // newer cache.
          cache =
              new Cache(
                  Map.copyOf(all),
                  Map.copyOf(active),
                  Map.copyOf(sentences),
                  Map.copyOf(kicks),
                  database.failureEpoch(),
                  decisionEpoch.get());
          return null;
        });
  }

  public Optional<Punishment> active(UUID playerId, Type type) {
    Cache current = cache;
    if (current == null) return Optional.empty();
    Punishment found = current.active().get(new ActiveKey(playerId, type));
    return found == null || isExpired(found, clock.instant())
        ? Optional.empty()
        : Optional.of(found);
  }

  public Optional<Punishment> find(UUID punishmentId) {
    Cache current = cache;
    return current == null
        ? Optional.empty()
        : Optional.ofNullable(current.punishments().get(punishmentId));
  }

  public Optional<Sentence> sentence(UUID targetId) {
    Cache current = cache;
    return current == null
        ? Optional.empty()
        : Optional.ofNullable(current.sentences().get(targetId));
  }

  public Collection<Sentence> sentences() {
    Cache current = cache;
    return current == null ? List.of() : List.copyOf(current.sentences().values());
  }

  /**
   * Includes expired deadlines so enforcement can durably record EXPIRED without disconnecting
   * anyone.
   */
  public Collection<Punishment> pendingKicks() {
    Cache current = cache;
    if (current == null) return List.of();
    return current.pendingKicks().keySet().stream()
        .map(current.punishments()::get)
        .filter(Objects::nonNull)
        .toList();
  }

  public Optional<Instant> kickDeadline(UUID punishmentId) {
    Cache current = cache;
    return current == null
        ? Optional.empty()
        : Optional.ofNullable(current.pendingKicks().get(punishmentId));
  }

  public CompletableFuture<List<Punishment>> pendingKicks(UUID targetId) {
    return refresh()
        .thenApply(
            ignored -> pendingKicks().stream().filter(p -> p.targetId().equals(targetId)).toList());
  }

  public CompletableFuture<List<Punishment>> history(UUID targetId) {
    return records(targetId, null);
  }

  /** Includes all warnings, even after delivery, acknowledgement, expiry or revocation. */
  public CompletableFuture<List<Punishment>> warnings(UUID targetId) {
    return records(targetId, Type.WARNING);
  }

  /** One-based pages of at most 50 entries; ordering is newest first, then stable UUID order. */
  public CompletableFuture<HistoryPage> history(UUID targetId, int page, int pageSize) {
    return page(targetId, null, page, pageSize);
  }

  public CompletableFuture<HistoryPage> warnings(UUID targetId, int page, int pageSize) {
    return page(targetId, Type.WARNING, page, pageSize);
  }

  private CompletableFuture<HistoryPage> page(UUID targetId, Type type, int page, int pageSize) {
    if (page < 1 || pageSize < 1 || pageSize > 50)
      return CompletableFuture.failedFuture(
          new IllegalArgumentException(
              "History needs a positive page and page size between 1 and 50"));
    return records(targetId, type)
        .thenApply(
            records -> {
              long offset = (long) (page - 1) * pageSize;
              int start = (int) Math.min(offset, records.size());
              int end = (int) Math.min(offset + pageSize, records.size());
              return new HistoryPage(records.subList(start, end), page, pageSize, records.size());
            });
  }

  private CompletableFuture<List<Punishment>> records(UUID targetId, Type type) {
    if (targetId == null)
      return CompletableFuture.failedFuture(
          new IllegalArgumentException("Target UUID is required"));
    return database.read(
        connection -> {
          List<Punishment> result = new ArrayList<>();
          try (PreparedStatement query =
              connection.prepareStatement(
                  type == null
                      ? "SELECT * FROM punishments WHERE target_id=?"
                      : "SELECT * FROM punishments WHERE target_id=? AND type=?")) {
            query.setString(1, targetId.toString());
            if (type != null) query.setString(2, type.name());
            try (ResultSet rows = query.executeQuery()) {
              while (rows.next()) result.add(readPunishment(rows));
            }
          }
          // ISO Instant strings use variable precision. SQL lexical ordering would put
          // ...:00Z ahead of ...:00.100Z, so retain exact Instant ordering for every page.
          result.sort(
              java.util.Comparator.comparing(Punishment::issuedAt)
                  .reversed()
                  .thenComparing(Punishment::id));
          return List.copyOf(result);
        });
  }

  public CompletableFuture<Void> expire() {
    return database
        .write(
            connection -> {
              Instant now = clock.instant();
              List<Punishment> expired = new ArrayList<>();
              try (var query = connection.createStatement();
                  var rows =
                      query.executeQuery(
                          "SELECT * FROM punishments WHERE status='ACTIVE' AND expires_at IS NOT"
                              + " NULL")) {
                while (rows.next()) {
                  Punishment punishment = readPunishment(rows);
                  if (isExpired(punishment, now)) expired.add(punishment);
                }
              }
              for (Punishment punishment : expired) expireOne(connection, punishment, now);
              List<UUID> expiredKicks = new ArrayList<>();
              try (var query = connection.createStatement();
                  var rows =
                      query.executeQuery(
                          "SELECT punishment_id,valid_until FROM pending_notifications WHERE"
                              + " notification_type='KICK' AND delivery_state='PENDING'")) {
                while (rows.next())
                  if (!Instant.parse(rows.getString(2)).isAfter(now))
                    expiredKicks.add(UUID.fromString(rows.getString(1)));
              }
              for (UUID id : expiredKicks) markKick(connection, id, "EXPIRED");
              return null;
            })
        .thenCompose(ignored -> refresh());
  }

  private void expireOne(Connection connection, Punishment punishment, Instant now)
      throws SQLException {
    try (PreparedStatement update =
        connection.prepareStatement(
            "UPDATE punishments SET status='EXPIRED',revision=revision+1 WHERE id=? AND"
                + " status='ACTIVE' AND revision=?")) {
      update.setString(1, punishment.id().toString());
      update.setLong(2, punishment.revision());
      if (update.executeUpdate() != 1) return;
    }
    markWorkComplete(connection, punishment);
    JsonObject payload = new JsonObject();
    payload.addProperty("expiredAt", now.toString());
    payload.addProperty("status", "EXPIRED");
    emit(
        connection,
        find(connection, punishment.id()).orElseThrow(),
        "punishment.expired",
        "SYSTEM",
        payload);
  }

  private static void markWorkComplete(Connection connection, Punishment punishment)
      throws SQLException {
    if (punishment.type() != Type.COMMUNITY_SERVICE) return;
    try (PreparedStatement update =
        connection.prepareStatement(
            "UPDATE community_service SET state='WORK_COMPLETE' WHERE punishment_id=? AND state NOT"
                + " IN ('COMPLETED','RESTORING')")) {
      update.setString(1, punishment.id().toString());
      update.executeUpdate();
    }
  }

  public CompletableFuture<Void> rememberPlayer(UUID playerId, String name) {
    if (playerId == null || name == null || !name.matches("[A-Za-z0-9_]{1,16}"))
      return CompletableFuture.failedFuture(
          new IllegalArgumentException("Invalid verified player identity"));
    return database.write(
        connection -> {
          String now = clock.instant().toString();
          try (PreparedStatement upsert =
              connection.prepareStatement(
                  "INSERT INTO players(uuid,last_name,first_seen,last_seen) VALUES (?,?,?,?) ON"
                      + " CONFLICT(uuid) DO UPDATE SET"
                      + " last_name=excluded.last_name,last_seen=excluded.last_seen")) {
            upsert.setString(1, playerId.toString());
            upsert.setString(2, name);
            upsert.setString(3, now);
            upsert.setString(4, now);
            upsert.executeUpdate();
          }
          return null;
        });
  }

  /**
   * Names come only from verified player logins; ambiguous or unknown historic names are rejected.
   */
  public CompletableFuture<Optional<UUID>> resolve(String input) {
    if (input == null) return CompletableFuture.completedFuture(Optional.empty());
    if (input.matches(
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
      return CompletableFuture.completedFuture(Optional.of(UUID.fromString(input)));
    }
    if (!input.matches("[A-Za-z0-9_]{1,16}"))
      return CompletableFuture.completedFuture(Optional.empty());
    return database.read(
        connection -> {
          try (PreparedStatement query =
              connection.prepareStatement(
                  "SELECT uuid FROM players WHERE last_name=? COLLATE NOCASE LIMIT 2")) {
            query.setString(1, input);
            try (ResultSet rows = query.executeQuery()) {
              if (!rows.next()) return Optional.empty();
              UUID id = UUID.fromString(rows.getString(1));
              return rows.next() ? Optional.empty() : Optional.of(id);
            }
          }
        });
  }

  public CompletableFuture<List<Punishment>> pendingWarnings(UUID playerId) {
    return database.read(
        connection -> {
          List<Punishment> warnings = new ArrayList<>();
          try (PreparedStatement query =
              connection.prepareStatement(
                  "SELECT p.* FROM punishments p JOIN pending_notifications n ON"
                      + " n.punishment_id=p.id WHERE n.player_id=? AND"
                      + " n.notification_type='WARNING' AND n.delivery_state='PENDING' AND"
                      + " p.status='ACTIVE'")) {
            query.setString(1, playerId.toString());
            try (ResultSet rows = query.executeQuery()) {
              while (rows.next()) {
                Punishment warning = readPunishment(rows);
                if (!isExpired(warning, clock.instant())) warnings.add(warning);
              }
            }
          }
          warnings.sort(java.util.Comparator.comparing(Punishment::issuedAt));
          return List.copyOf(warnings);
        });
  }

  public CompletableFuture<Void> markWarningDelivered(UUID punishmentId) {
    return database
        .write(
            connection -> {
              Optional<Punishment> found = find(connection, punishmentId);
              if (found.isEmpty() || found.get().type() != Type.WARNING)
                throw new IllegalArgumentException("Warning does not exist");
              try (PreparedStatement update =
                  connection.prepareStatement(
                      "UPDATE pending_notifications SET delivery_state='DELIVERED',delivered_at=?"
                          + " WHERE punishment_id=? AND notification_type='WARNING' AND"
                          + " delivery_state='PENDING'")) {
                update.setString(1, clock.instant().toString());
                update.setString(2, punishmentId.toString());
                if (update.executeUpdate() == 1) {
                  advanceRevision(connection, found.get());
                  emit(
                      connection,
                      find(connection, punishmentId).orElseThrow(),
                      "warning.delivered",
                      "SYSTEM",
                      new JsonObject());
                }
              }
              return null;
            })
        .thenCompose(ignored -> refresh());
  }

  public CompletableFuture<Void> acknowledgeWarning(UUID playerId, UUID punishmentId) {
    Optional<Punishment> cached = find(punishmentId);
    if (cached.isEmpty()
        || cached.get().type() != Type.WARNING
        || !cached.get().targetId().equals(playerId)) {
      return CompletableFuture.failedFuture(
          new IllegalArgumentException("Warning does not belong to this player"));
    }
    return database
        .write(
            connection -> {
              Optional<Punishment> found = find(connection, punishmentId);
              if (found.isEmpty()
                  || found.get().type() != Type.WARNING
                  || !found.get().targetId().equals(playerId)) return false;
              try (PreparedStatement query =
                  connection.prepareStatement(
                      "SELECT delivery_state FROM pending_notifications WHERE punishment_id=? AND"
                          + " notification_type='WARNING'")) {
                query.setString(1, punishmentId.toString());
                try (ResultSet rows = query.executeQuery()) {
                  if (!rows.next() || !"DELIVERED".equals(rows.getString(1))) return false;
                }
              }
              try (PreparedStatement update =
                  connection.prepareStatement(
                      "UPDATE pending_notifications SET acknowledged_at=? WHERE punishment_id=? AND"
                          + " player_id=? AND notification_type='WARNING' AND"
                          + " delivery_state='DELIVERED' AND acknowledged_at IS NULL")) {
                update.setString(1, clock.instant().toString());
                update.setString(2, punishmentId.toString());
                update.setString(3, playerId.toString());
                if (update.executeUpdate() == 1) {
                  advanceRevision(connection, found.get());
                  emit(
                      connection,
                      find(connection, punishmentId).orElseThrow(),
                      "warning.acknowledged",
                      playerId.toString(),
                      new JsonObject());
                }
              }
              return true;
            })
        .thenCompose(
            valid ->
                valid
                    ? refresh()
                    : CompletableFuture.failedFuture(
                        new IllegalArgumentException(
                            "Warning has not been delivered to this player")));
  }

  public CompletableFuture<Void> markKick(UUID punishmentId, String state) {
    if (!Set.of("EXECUTED", "FAILED", "EXPIRED").contains(state))
      return CompletableFuture.failedFuture(
          new IllegalArgumentException("Invalid kick delivery state"));
    return database
        .write(
            connection -> {
              markKick(connection, punishmentId, state);
              return null;
            })
        .thenCompose(ignored -> refresh());
  }

  /** Persist before touching the player, so a crash cannot replay an uncertain disconnection. */
  public CompletableFuture<Boolean> claimKick(UUID punishmentId) {
    return database
        .write(
            connection -> {
              Optional<Punishment> found = find(connection, punishmentId);
              if (found.isEmpty() || found.get().type() != Type.KICK) return false;
              try (PreparedStatement query =
                  connection.prepareStatement(
                      "SELECT valid_until FROM pending_notifications WHERE punishment_id=? AND"
                          + " notification_type='KICK' AND delivery_state='PENDING'")) {
                query.setString(1, punishmentId.toString());
                try (ResultSet rows = query.executeQuery()) {
                  if (!rows.next()) return false;
                  if (!Instant.parse(rows.getString(1)).isAfter(clock.instant())) {
                    markKick(connection, punishmentId, "EXPIRED");
                    return false;
                  }
                }
              }
              try (PreparedStatement update =
                  connection.prepareStatement(
                      "UPDATE pending_notifications SET delivery_state='EXECUTING' WHERE"
                          + " punishment_id=? AND notification_type='KICK' AND"
                          + " delivery_state='PENDING'")) {
                update.setString(1, punishmentId.toString());
                if (update.executeUpdate() != 1) return false;
              }
              advanceRevision(connection, found.get());
              JsonObject payload = new JsonObject();
              payload.addProperty("deliveryState", "EXECUTING");
              emit(
                  connection,
                  find(connection, punishmentId).orElseThrow(),
                  "kick.claimed",
                  "SYSTEM",
                  payload);
              return true;
            })
        .thenCompose(claimed -> refresh().thenApply(ignored -> claimed));
  }

  /**
   * Startup only: never retry a kick whose previous process may already have disconnected its
   * player.
   */
  public CompletableFuture<Void> recoverInterruptedKicks() {
    return database
        .write(
            connection -> {
              List<UUID> uncertain = new ArrayList<>();
              try (var query = connection.createStatement();
                  var rows =
                      query.executeQuery(
                          "SELECT punishment_id FROM pending_notifications WHERE"
                              + " notification_type='KICK' AND delivery_state='EXECUTING'")) {
                while (rows.next()) uncertain.add(UUID.fromString(rows.getString(1)));
              }
              for (UUID id : uncertain) markKick(connection, id, "FAILED");
              return null;
            })
        .thenCompose(ignored -> refresh());
  }

  private void markKick(Connection connection, UUID punishmentId, String state)
      throws SQLException {
    Optional<Punishment> found = find(connection, punishmentId);
    if (found.isEmpty() || found.get().type() != Type.KICK)
      throw new IllegalArgumentException("Kick does not exist");
    String effectiveState = state;
    try (PreparedStatement query =
        connection.prepareStatement(
            "SELECT valid_until,delivery_state FROM pending_notifications WHERE punishment_id=? AND"
                + " notification_type='KICK' AND delivery_state IN ('PENDING','EXECUTING')")) {
      query.setString(1, punishmentId.toString());
      try (ResultSet rows = query.executeQuery()) {
        if (!rows.next()) return;
        if ("EXECUTED".equals(state) && !"EXECUTING".equals(rows.getString(2))) return;
        if (!Instant.parse(rows.getString(1)).isAfter(clock.instant())
            && "PENDING".equals(rows.getString(2))) effectiveState = "EXPIRED";
      }
    }
    try (PreparedStatement update =
        connection.prepareStatement(
            "UPDATE pending_notifications SET delivery_state=?,delivered_at=? WHERE punishment_id=?"
                + " AND notification_type='KICK' AND delivery_state IN ('PENDING','EXECUTING')")) {
      update.setString(1, effectiveState);
      update.setString(2, clock.instant().toString());
      update.setString(3, punishmentId.toString());
      if (update.executeUpdate() == 1) {
        advanceRevision(connection, found.get());
        JsonObject payload = new JsonObject();
        payload.addProperty("deliveryState", effectiveState);
        emit(
            connection,
            find(connection, punishmentId).orElseThrow(),
            "kick." + effectiveState.toLowerCase(Locale.ROOT),
            "SYSTEM",
            payload);
      }
    }
  }

  private static void queueAppealRequest(Connection connection, UUID punishmentId, Instant now)
      throws SQLException {
    try (PreparedStatement insert =
        connection.prepareStatement(
            "INSERT INTO appeal_requests(punishment_id,provisioning_id,created_at,state) VALUES"
                + " (?,?,?,'PENDING') ON CONFLICT(punishment_id) DO UPDATE SET"
                + " provisioning_id=excluded.provisioning_id,created_at=excluded.created_at,state='PENDING'")) {
      insert.setString(1, punishmentId.toString());
      insert.setString(2, UUID.randomUUID().toString());
      insert.setString(3, now.toString());
      insert.executeUpdate();
    }
  }

  /**
   * Inserts both records in the caller's transaction. Private appeal/snapshot fields are removed
   * recursively.
   */
  public void emit(
      Connection connection,
      Punishment punishment,
      String eventType,
      String actor,
      JsonObject payload)
      throws SQLException {
    if (connection.getAutoCommit())
      throw new IllegalStateException("Audit events require a transaction");
    // Enforcement remains fail closed between the local decision commit and cache publication.
    decisionEpoch.incrementAndGet();
    UUID eventId = UUID.randomUUID();
    String now = clock.instant().toString();
    JsonObject publicPayload =
        sanitize(payload == null ? new JsonObject() : payload).getAsJsonObject();
    publicPayload.addProperty("actor", actor);
    JsonObject envelope = new JsonObject();
    envelope.addProperty("schemaVersion", 1);
    envelope.addProperty("eventId", eventId.toString());
    envelope.addProperty("serverId", serverId);
    envelope.addProperty("eventType", eventType);
    envelope.addProperty("timestamp", now);
    envelope.addProperty("targetPlayerUuid", punishment.targetId().toString());
    envelope.addProperty("punishmentId", punishment.id().toString());
    envelope.addProperty("revision", punishment.revision());
    envelope.add("payload", publicPayload);
    try (PreparedStatement insert =
        connection.prepareStatement(
            "INSERT INTO"
                + " punishment_events(event_id,punishment_id,event_type,actor,created_at,revision,payload)"
                + " VALUES (?,?,?,?,?,?,?)")) {
      insert.setString(1, eventId.toString());
      insert.setString(2, punishment.id().toString());
      insert.setString(3, eventType);
      insert.setString(4, actor);
      insert.setString(5, now);
      insert.setLong(6, punishment.revision());
      insert.setString(7, publicPayload.toString());
      insert.executeUpdate();
    }
    try (PreparedStatement insert =
        connection.prepareStatement(
            "INSERT INTO"
                + " sync_outbox(event_id,event_type,payload,created_at,retry_count,delivery_state)"
                + " VALUES (?,?,?,?,0,'PENDING')")) {
      insert.setString(1, eventId.toString());
      insert.setString(2, eventType);
      insert.setString(3, envelope.toString());
      insert.setString(4, now);
      insert.executeUpdate();
    }
  }

  public static Optional<Punishment> find(Connection connection, UUID id) throws SQLException {
    try (PreparedStatement query =
        connection.prepareStatement("SELECT * FROM punishments WHERE id=?")) {
      query.setString(1, id.toString());
      try (ResultSet rows = query.executeQuery()) {
        return rows.next() ? Optional.of(readPunishment(rows)) : Optional.empty();
      }
    }
  }

  private static Optional<Punishment> activeFromDatabase(
      Connection connection, UUID targetId, Type type) throws SQLException {
    try (PreparedStatement query =
        connection.prepareStatement(
            "SELECT * FROM punishments WHERE target_id=? AND type=? AND status='ACTIVE'")) {
      query.setString(1, targetId.toString());
      query.setString(2, type.name());
      try (ResultSet rows = query.executeQuery()) {
        return rows.next() ? Optional.of(readPunishment(rows)) : Optional.empty();
      }
    }
  }

  private static Optional<Sentence> findSentence(Connection connection, UUID targetId)
      throws SQLException {
    try (PreparedStatement query =
        connection.prepareStatement(
            "SELECT * FROM community_service WHERE target_id=? AND state<>'COMPLETED'")) {
      query.setString(1, targetId.toString());
      try (ResultSet rows = query.executeQuery()) {
        return rows.next() ? Optional.of(readSentence(rows)) : Optional.empty();
      }
    }
  }

  private static Optional<String> knownName(Connection connection, UUID targetId)
      throws SQLException {
    try (PreparedStatement query =
        connection.prepareStatement("SELECT last_name FROM players WHERE uuid=?")) {
      query.setString(1, targetId.toString());
      try (ResultSet rows = query.executeQuery()) {
        return rows.next() ? Optional.of(rows.getString(1)) : Optional.empty();
      }
    }
  }

  public static Punishment readPunishment(ResultSet rows) throws SQLException {
    return new Punishment(
        UUID.fromString(rows.getString("id")),
        rows.getString("reference"),
        UUID.fromString(rows.getString("target_id")),
        rows.getString("target_name"),
        Type.valueOf(rows.getString("type")),
        rows.getString("reason"),
        rows.getString("issuer"),
        Source.valueOf(rows.getString("source")),
        Instant.parse(rows.getString("issued_at")),
        parseOptionalInstant(rows.getString("expires_at")),
        Status.valueOf(rows.getString("status")),
        rows.getLong("revision"),
        rows.getString("appeal_url"));
  }

  private static Sentence readSentence(ResultSet rows) throws SQLException {
    return new Sentence(
        UUID.fromString(rows.getString("id")),
        UUID.fromString(rows.getString("punishment_id")),
        UUID.fromString(rows.getString("target_id")),
        rows.getInt("required"),
        rows.getInt("completed"),
        rows.getString("tool"),
        rows.getString("state"));
  }

  private static void advanceRevision(Connection connection, Punishment punishment)
      throws SQLException {
    if (punishment.revision() == Long.MAX_VALUE)
      throw new SQLException("Punishment revision exhausted");
    try (PreparedStatement update =
        connection.prepareStatement(
            "UPDATE punishments SET revision=revision+1 WHERE id=? AND revision=?")) {
      update.setString(1, punishment.id().toString());
      update.setLong(2, punishment.revision());
      requireChanged(update);
    }
  }

  private static void requireChanged(PreparedStatement update) throws SQLException {
    if (update.executeUpdate() != 1) throw new SQLException("Concurrent or missing punishment row");
  }

  private static String newReference(Connection connection) throws SQLException {
    for (int attempt = 0; attempt < 10; attempt++) {
      String reference =
          "MP-"
              + UUID.randomUUID()
                  .toString()
                  .replace("-", "")
                  .substring(0, 8)
                  .toUpperCase(Locale.ROOT);
      try (PreparedStatement query =
          connection.prepareStatement("SELECT 1 FROM punishments WHERE reference=?")) {
        query.setString(1, reference);
        try (ResultSet rows = query.executeQuery()) {
          if (!rows.next()) return reference;
        }
      }
    }
    throw new SQLException("Could not allocate unique public punishment reference");
  }

  private String reason(JsonObject payload, String fallback) {
    String reason = payload.has("reason") ? string(payload, "reason", true).strip() : fallback;
    if (reason == null
        || reason.isBlank()
        || reason.length() > limits.maximumReasonLength()
        || hasControls(reason)) {
      throw invalid(
          "INVALID_REASON",
          "Reason must contain 1-"
              + limits.maximumReasonLength()
              + " characters without control characters");
    }
    return reason;
  }

  private int blocks(JsonObject payload) {
    if (!payload.has("blocks")
        || !payload.get("blocks").isJsonPrimitive()
        || !payload.get("blocks").getAsJsonPrimitive().isNumber())
      throw invalid("INVALID_BLOCKS", "An integer block count is required");
    int count;
    try {
      count = payload.get("blocks").getAsBigDecimal().intValueExact();
    } catch (ArithmeticException | NumberFormatException failure) {
      throw invalid("INVALID_BLOCKS", "Block count must be a whole number");
    }
    if (count < limits.minimumBlocks() || count > limits.maximumBlocks())
      throw invalid(
          "INVALID_BLOCKS",
          "Block count must be between "
              + limits.minimumBlocks()
              + " and "
              + limits.maximumBlocks());
    return count;
  }

  private static Instant expiration(JsonObject payload) {
    if (!payload.has("expiresAt") || payload.get("expiresAt").isJsonNull()) return null;
    String value = string(payload, "expiresAt", true);
    try {
      if (!value.endsWith("Z")) throw new DateTimeParseException("UTC required", value, 0);
      return Instant.parse(value);
    } catch (DateTimeParseException failure) {
      throw invalid(
          "INVALID_EXPIRATION", "Expiration must be an ISO-8601 UTC timestamp ending in Z");
    }
  }

  private static String string(JsonObject payload, String field, boolean required) {
    JsonElement value = payload.get(field);
    if (value == null || value.isJsonNull()) {
      if (!required) return null;
      throw invalid("INVALID_PAYLOAD", "Missing string field: " + field);
    }
    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
      throw invalid("INVALID_PAYLOAD", "Field must be a string: " + field);
    return value.getAsString();
  }

  private void validateAppealUrl(String value) {
    try {
      URI uri = URI.create(value);
      URI trusted = appealBase;
      if (!"https".equalsIgnoreCase(uri.getScheme())
          || !trusted.getHost().equalsIgnoreCase(uri.getHost())
          || uri.getUserInfo() != null
          || uri.getFragment() != null
          || uri.getQuery() != null
          || effectivePort(uri) != effectivePort(trusted)
          || uri.getRawPath() == null
          || !uri.getRawPath().startsWith(trusted.getRawPath())) {
        throw invalid(
            "INVALID_APPEAL_URL",
            "Appeal URL must be a provisioned HTTPS link under the configured appeal base");
      }
      String token = uri.getRawPath().substring(trusted.getRawPath().length());
      if (!token.matches("[A-Za-z0-9_-]{43,128}")
          || Base64.getUrlDecoder().decode(token).length < 32)
        throw invalid(
            "INVALID_APPEAL_URL",
            "Appeal URL must contain a backend-provisioned 256-bit URL-safe token");
    } catch (IllegalArgumentException failure) {
      throw invalid("INVALID_APPEAL_URL", "Appeal URL is invalid");
    }
  }

  private static int effectivePort(URI uri) {
    return uri.getPort() == -1 ? 443 : uri.getPort();
  }

  private static boolean hasControls(String value) {
    return value.codePoints().anyMatch(Character::isISOControl);
  }

  private static boolean isCreation(ActionType type) {
    return type != null && type.name().startsWith("CREATE_");
  }

  private static boolean isExpired(Punishment punishment, Instant now) {
    return punishment.expiresAt() != null && !punishment.expiresAt().isAfter(now);
  }

  private static String instantText(Instant instant) {
    return instant == null ? null : instant.toString();
  }

  private static Instant parseOptionalInstant(String value) {
    return value == null ? null : Instant.parse(value);
  }

  private static void putNullable(JsonObject object, String name, String value) {
    if (value == null) object.add(name, JsonNull.INSTANCE);
    else object.addProperty(name, value);
  }

  private static Result rejected(String code, String detail, Punishment punishment) {
    return new Result(false, code, detail, punishment);
  }

  private static InvalidAction invalid(String code, String detail) {
    return new InvalidAction(code, detail);
  }

  private static final class InvalidAction extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String code;

    private InvalidAction(String code, String detail) {
      super(detail);
      this.code = code;
    }
  }

  private static JsonObject publicPunishment(Punishment p) {
    JsonObject json = new JsonObject();
    json.addProperty("reference", p.reference());
    json.addProperty("targetName", p.targetName());
    json.addProperty("type", p.type().name());
    json.addProperty("reason", p.reason());
    json.addProperty("issuer", p.issuer());
    json.addProperty("source", p.source().name());
    json.addProperty("issuedAt", p.issuedAt().toString());
    putNullable(json, "expiresAt", instantText(p.expiresAt()));
    json.addProperty("status", p.status().name());
    return json;
  }

  private static JsonElement sanitize(JsonElement value) {
    if (value.isJsonObject()) {
      JsonObject result = new JsonObject();
      for (var entry : value.getAsJsonObject().entrySet()) {
        String key = entry.getKey().toLowerCase(Locale.ROOT).replace("_", "");
        if (key.contains("token")
            || key.contains("secret")
            || key.contains("credential")
            || key.contains("snapshot")
            || key.contains("inventory")
            || key.contains("session")
            || key.contains("authorization")
            || key.contains("serialized")
            || key.contains("url")
            || key.contains("appeal")) continue;
        result.add(entry.getKey(), sanitize(entry.getValue()));
      }
      return result;
    }
    if (value.isJsonArray()) {
      JsonArray result = new JsonArray();
      for (JsonElement item : value.getAsJsonArray()) result.add(sanitize(item));
      return result;
    }
    return value.deepCopy();
  }

  private static String actionHash(Action action) {
    JsonObject json = new JsonObject();
    putNullable(json, "id", action.id() == null ? null : action.id().toString());
    putNullable(json, "type", action.type() == null ? null : action.type().name());
    putNullable(json, "targetId", action.targetId() == null ? null : action.targetId().toString());
    putNullable(json, "targetName", action.targetName());
    putNullable(
        json,
        "punishmentId",
        action.punishmentId() == null ? null : action.punishmentId().toString());
    if (action.expectedRevision() == null) json.add("expectedRevision", JsonNull.INSTANCE);
    else json.addProperty("expectedRevision", action.expectedRevision());
    putNullable(json, "issuer", action.issuer());
    putNullable(json, "source", action.source() == null ? null : action.source().name());
    putNullable(json, "issuedAt", instantText(action.issuedAt()));
    putNullable(json, "validUntil", instantText(action.validUntil()));
    json.add("payload", action.payload());
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(canonicalize(json).toString().getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }

  private static JsonElement canonicalize(JsonElement value) {
    if (value.isJsonObject()) {
      JsonObject result = new JsonObject();
      new TreeMap<>(value.getAsJsonObject().asMap())
          .forEach((key, item) -> result.add(key, canonicalize(item)));
      return result;
    }
    if (value.isJsonArray()) {
      JsonArray result = new JsonArray();
      for (JsonElement item : value.getAsJsonArray()) result.add(canonicalize(item));
      return result;
    }
    return value.deepCopy();
  }

  private static JsonObject resultJson(Result result) {
    JsonObject json = new JsonObject();
    json.addProperty("applied", result.applied());
    json.addProperty("code", result.code());
    json.addProperty("detail", result.detail());
    if (result.punishment() != null) {
      Punishment p = result.punishment();
      JsonObject punishment = publicPunishment(p);
      punishment.addProperty("id", p.id().toString());
      punishment.addProperty("targetId", p.targetId().toString());
      punishment.addProperty("revision", p.revision());
      putNullable(punishment, "appealUrl", p.appealUrl());
      json.add("punishment", punishment);
    }
    return json;
  }

  private static Result readResult(JsonObject json) {
    Punishment punishment = null;
    if (json.has("punishment")) {
      JsonObject p = json.getAsJsonObject("punishment");
      punishment =
          new Punishment(
              UUID.fromString(p.get("id").getAsString()),
              p.get("reference").getAsString(),
              UUID.fromString(p.get("targetId").getAsString()),
              p.get("targetName").getAsString(),
              Type.valueOf(p.get("type").getAsString()),
              p.get("reason").getAsString(),
              p.get("issuer").getAsString(),
              Source.valueOf(p.get("source").getAsString()),
              Instant.parse(p.get("issuedAt").getAsString()),
              p.get("expiresAt").isJsonNull()
                  ? null
                  : Instant.parse(p.get("expiresAt").getAsString()),
              Status.valueOf(p.get("status").getAsString()),
              p.get("revision").getAsLong(),
              p.get("appealUrl").isJsonNull() ? null : p.get("appealUrl").getAsString());
    }
    return new Result(
        json.get("applied").getAsBoolean(),
        json.get("code").getAsString(),
        json.get("detail").getAsString(),
        punishment);
  }
}
