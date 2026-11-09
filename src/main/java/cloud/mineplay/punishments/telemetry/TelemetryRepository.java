package cloud.mineplay.punishments.telemetry;

import static cloud.mineplay.punishments.telemetry.TelemetryObservation.*;

import cloud.mineplay.punishments.storage.Database;
import com.google.gson.*;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Durable private telemetry state. Every database operation runs on Database's worker. */
public final class TelemetryRepository {
  private static final DateTimeFormatter UTC =
      new DateTimeFormatterBuilder().appendInstant(6).toFormatter();
  private final Database database;
  private final String serverId;

  /** The JSON string is a committed, frozen player item, never a live mutable JSON object. */
  public record Pending(UUID playerId, long revision, String json) {}

  private record State(long revision, JsonObject base, boolean dirty) {}

  private record StoredSession(
      String id, String joined, String left, long changed, long acknowledged) {}

  public TelemetryRepository(Database database, String serverId) {
    this.database = Objects.requireNonNull(database);
    if (serverId == null || !serverId.matches("[A-Za-z0-9_-]{1,64}"))
      throw new IllegalArgumentException("Invalid telemetry server ID");
    this.serverId = serverId;
  }

  public CompletableFuture<Void> initialize() {
    return database.write(
        connection -> {
          String ddl;
          try (InputStream stream =
              TelemetryRepository.class.getResourceAsStream("/db/telemetry.sql")) {
            if (stream == null) throw new IllegalStateException("Telemetry schema is unavailable");
            ddl = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
          }
          for (String statement : ddl.split(";"))
            if (!statement.isBlank())
              try (Statement sql = connection.createStatement()) {
                sql.execute(statement);
              }
          return null;
        });
  }

  /** Incomplete or regressive observations do not alter history or invalidate moderation health. */
  public CompletableFuture<Boolean> observe(TelemetryObservation observation) {
    Objects.requireNonNull(observation);
    return database.write(connection -> observe(connection, observation));
  }

  private boolean observe(Connection connection, TelemetryObservation observation)
      throws SQLException {
    String id = observation.playerId().toString();
    State prior = state(connection, id);
    JsonObject base = base(observation);
    for (Session session : observation.sessions()) {
      Instant knownLeft = nullableInstant(base, "lastLeftAt");
      if (session.leftAt() != null && (knownLeft == null || session.leftAt().isAfter(knownLeft)))
        base.addProperty("lastLeftAt", time(session.leftAt()));
    }
    if (prior != null) {
      if (observation.observedAt().isBefore(instant(prior.base(), "observedAt"))
          || observation.lastJoinedAt().isBefore(instant(prior.base(), "lastJoinedAt")))
        return false;
      earlier(base, prior.base(), "firstJoinedAt");
      laterNullable(base, prior.base(), "lastLeftAt");
      mergeCounter(base, prior.base(), "totalPlaySeconds", 0);
      long newLogins =
          observation.sessions().stream()
              .filter(s -> s.joinedAt().isAfter(instant(prior.base(), "lastJoinedAt")))
              .count();
      mergeCounter(base, prior.base(), "loginCount", newLogins);
    }
    Instant left = nullableInstant(base, "lastLeftAt");
    Instant joined = instant(base, "lastJoinedAt");
    if (left != null && (observation.online() ? left.isAfter(joined) : left.isBefore(joined)))
      return false;
    List<StoredSession> existing = sessions(connection, id);
    Map<String, StoredSession> byId = new HashMap<>();
    existing.forEach(s -> byId.put(s.id(), s));
    List<Session> changedSessions = new ArrayList<>();
    for (Session incoming : observation.sessions()) {
      StoredSession current = byId.get(incoming.sessionId().toString());
      if (current != null) {
        if (!current.joined().equals(time(incoming.joinedAt()))) return false;
        if (current.left() != null) {
          // An omitted logout cannot reopen a record; a different known logout is a conflict.
          if (incoming.leftAt() != null && !current.left().equals(time(incoming.leftAt())))
            return false;
          continue;
        }
        if (incoming.leftAt() == null) continue;
      }
      changedSessions.add(incoming);
    }
    Map<String, String> addresses = addresses(connection, id);
    boolean changedAddress = false;
    for (Address address : observation.recentAddresses()) {
      String seen = time(address.lastSeenAt());
      String old = addresses.get(address.address());
      if (old == null || old.compareTo(seen) < 0) {
        addresses.put(address.address(), seen);
        changedAddress = true;
      }
    }
    if (prior != null && prior.base().equals(base) && changedSessions.isEmpty() && !changedAddress)
      return true;
    long revision = prior == null ? 1 : prior.revision() + 1;
    if (revision <= 0 || revision >= Long.MAX_VALUE) return false;
    try (PreparedStatement sql =
        connection.prepareStatement(
            "INSERT INTO"
                + " telemetry_players(server_id,player_id,revision,observation_json,needs_publish)"
                + " VALUES (?,?,?,?,1) ON CONFLICT(server_id,player_id) DO UPDATE SET"
                + " revision=excluded.revision,"
                + " observation_json=excluded.observation_json,needs_publish=1,rejection_category=NULL")) {
      sql.setString(1, serverId);
      sql.setString(2, id);
      sql.setLong(3, revision);
      sql.setString(4, base.toString());
      sql.executeUpdate();
    }
    for (Session session : changedSessions) {
      try (PreparedStatement sql =
          connection.prepareStatement(
              "INSERT INTO"
                  + " telemetry_sessions(server_id,player_id,session_id,joined_at,left_at,changed_revision)"
                  + " VALUES (?,?,?,?,?,?) ON CONFLICT(server_id,player_id,session_id) DO UPDATE"
                  + " SET left_at=excluded.left_at,changed_revision=excluded.changed_revision")) {
        sql.setString(1, serverId);
        sql.setString(2, id);
        sql.setString(3, session.sessionId().toString());
        sql.setString(4, time(session.joinedAt()));
        sql.setString(5, time(session.leftAt()));
        sql.setLong(6, revision);
        sql.executeUpdate();
      }
    }
    // Keep at most the latest 1,000 records. Fixed microsecond UTC strings sort chronologically.
    try (PreparedStatement sql =
        connection.prepareStatement(
            "DELETE FROM telemetry_sessions WHERE server_id=? AND player_id=? AND session_id IN"
                + " (SELECT session_id FROM telemetry_sessions WHERE server_id=? AND player_id=?"
                + " ORDER BY joined_at DESC,session_id DESC LIMIT -1 OFFSET 1000)")) {
      bindIdentity(sql, id);
      sql.setString(3, serverId);
      sql.setString(4, id);
      sql.executeUpdate();
    }
    if (changedAddress) {
      try (PreparedStatement sql =
          connection.prepareStatement(
              "DELETE FROM telemetry_addresses WHERE server_id=? AND player_id=?")) {
        bindIdentity(sql, id);
        sql.executeUpdate();
      }
      List<Map.Entry<String, String>> newest = new ArrayList<>(addresses.entrySet());
      newest.sort(
          Map.Entry.<String, String>comparingByValue()
              .reversed()
              .thenComparing(Map.Entry.comparingByKey()));
      for (var address : newest.stream().limit(5).toList()) {
        try (PreparedStatement sql =
            connection.prepareStatement(
                "INSERT INTO telemetry_addresses(server_id,player_id,address,last_seen_at) VALUES"
                    + " (?,?,?,?)")) {
          bindIdentity(sql, id);
          sql.setString(3, address.getKey());
          sql.setString(4, address.getValue());
          sql.executeUpdate();
        }
      }
    }
    return true;
  }

  /**
   * Freeze before HTTP. New observations can update the next state without changing this snapshot.
   */
  public CompletableFuture<List<Pending>> nextBatch(int maxPlayers, int jsonBudgetBytes) {
    if (maxPlayers < 1 || maxPlayers > 100 || jsonBudgetBytes < 2 || jsonBudgetBytes >= 1024 * 1024)
      throw new IllegalArgumentException("Invalid telemetry batch limit");
    return database.write(
        connection -> {
          List<Pending> result = new ArrayList<>();
          int used = 2;
          try (PreparedStatement sql =
              connection.prepareStatement(
                  "SELECT player_id,revision,snapshot_json FROM telemetry_pending WHERE server_id=?"
                      + " ORDER BY rowid LIMIT ?")) {
            sql.setString(1, serverId);
            sql.setInt(2, maxPlayers);
            try (ResultSet rows = sql.executeQuery()) {
              while (rows.next()) {
                Pending pending =
                    new Pending(
                        UUID.fromString(rows.getString(1)), rows.getLong(2), rows.getString(3));
                int bytes =
                    pending.json().getBytes(StandardCharsets.UTF_8).length
                        + (result.isEmpty() ? 0 : 1);
                if (used + bytes <= jsonBudgetBytes) {
                  result.add(pending);
                  used += bytes;
                }
              }
            }
          }
          if (result.size() == maxPlayers) return List.copyOf(result);
          // Reserve a historical slot and rotate each lane by persisted dispatch order. Frequent
          // refreshes of early roster IDs must not starve later online players or the backfill.
          LinkedHashSet<String> candidates = new LinkedHashSet<>();
          int available = maxPlayers - result.size();
          if (available > 1) {
            candidates.addAll(candidates(connection, Math.max(1, available * 9 / 10), true));
            candidates.addAll(candidates(connection, Math.max(1, available / 10), false));
          }
          candidates.addAll(candidates(connection, available, null));
          for (String id : candidates) {
            if (result.size() == maxPlayers) break;
            State current = state(connection, id);
            JsonObject payload = current.base().deepCopy();
            payload.addProperty("revision", current.revision());
            JsonArray sessionArray = new JsonArray();
            sessions(connection, id).stream()
                .filter(s -> s.changed() > s.acknowledged())
                .sorted(
                    Comparator.comparingLong(StoredSession::changed)
                        .thenComparing(StoredSession::joined)
                        .thenComparing(StoredSession::id))
                .limit(100)
                .forEach(
                    s -> {
                      JsonObject item = new JsonObject();
                      item.addProperty("sessionId", s.id());
                      item.addProperty("joinedAt", s.joined());
                      item.addProperty("leftAt", s.left());
                      sessionArray.add(item);
                    });
            payload.add("sessions", sessionArray);
            JsonArray addressArray = new JsonArray();
            addresses(connection, id).entrySet().stream()
                .sorted(
                    Map.Entry.<String, String>comparingByValue()
                        .reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .forEach(
                    a -> {
                      JsonObject item = new JsonObject();
                      item.addProperty("address", a.getKey());
                      item.addProperty("lastSeenAt", a.getValue());
                      addressArray.add(item);
                    });
            payload.add("recentAddresses", addressArray);
            String frozen = payload.toString();
            int bytes = frozen.getBytes(StandardCharsets.UTF_8).length + (result.isEmpty() ? 0 : 1);
            if (used + bytes > jsonBudgetBytes) continue;
            try (PreparedStatement sql =
                connection.prepareStatement(
                    "INSERT INTO telemetry_pending(server_id,player_id,revision,snapshot_json)"
                        + " VALUES (?,?,?,?)")) {
              bindIdentity(sql, id);
              sql.setLong(3, current.revision());
              sql.setString(4, frozen);
              sql.executeUpdate();
            }
            try (PreparedStatement sql =
                connection.prepareStatement(
                    "UPDATE telemetry_players SET queue_order=(SELECT"
                        + " COALESCE(MAX(queue_order),0)+1 FROM telemetry_players WHERE"
                        + " server_id=?) WHERE server_id=? AND player_id=?")) {
              sql.setString(1, serverId);
              sql.setString(2, serverId);
              sql.setString(3, id);
              sql.executeUpdate();
            }
            result.add(new Pending(UUID.fromString(id), current.revision(), frozen));
            used += bytes;
          }
          return List.copyOf(result);
        });
  }

  public CompletableFuture<Void> acknowledge(
      List<Pending> batch, Set<UUID> accepted, Set<UUID> ignored) {
    List<Pending> sent = List.copyOf(batch);
    Set<UUID> expected = new HashSet<>();
    sent.forEach(
        item -> {
          if (!expected.add(item.playerId()))
            throw new IllegalArgumentException("Duplicate telemetry acknowledgement");
        });
    Set<UUID> delivered = new HashSet<>(accepted);
    if (!Collections.disjoint(accepted, ignored))
      throw new IllegalArgumentException("Invalid telemetry acknowledgement");
    delivered.addAll(ignored);
    if (!delivered.equals(expected))
      throw new IllegalArgumentException("Incomplete telemetry acknowledgement");
    return database.write(
        connection -> {
          for (Pending pending : sent) {
            if (!matchesPending(connection, pending)) continue;
            JsonObject snapshot = JsonParser.parseString(pending.json()).getAsJsonObject();
            for (JsonElement item : snapshot.getAsJsonArray("sessions")) {
              JsonObject session = item.getAsJsonObject();
              try (PreparedStatement sql =
                  connection.prepareStatement(
                      "UPDATE telemetry_sessions SET acknowledged_revision=changed_revision WHERE"
                          + " server_id=? AND player_id=? AND session_id=? AND joined_at=? AND"
                          + " left_at IS ?")) {
                bindIdentity(sql, pending.playerId().toString());
                sql.setString(3, session.get("sessionId").getAsString());
                sql.setString(4, session.get("joinedAt").getAsString());
                sql.setString(5, nullableString(session, "leftAt"));
                sql.executeUpdate();
              }
            }
            deletePending(connection, pending);
            State current = state(connection, pending.playerId().toString());
            boolean moreSessions =
                sessions(connection, pending.playerId().toString()).stream()
                    .anyMatch(s -> s.changed() > s.acknowledged());
            boolean newer = current.revision() > pending.revision();
            long next = current.revision();
            // A different history page is a changed snapshot even without another server
            // observation.
            if (moreSessions && !newer && next < Long.MAX_VALUE - 1) next++;
            boolean dirty = newer || moreSessions && next > pending.revision();
            try (PreparedStatement sql =
                connection.prepareStatement(
                    "UPDATE telemetry_players SET"
                        + " revision=?,needs_publish=?,rejection_category=NULL WHERE server_id=?"
                        + " AND player_id=?")) {
              sql.setLong(1, next);
              sql.setInt(2, dirty ? 1 : 0);
              sql.setString(3, serverId);
              sql.setString(4, pending.playerId().toString());
              sql.executeUpdate();
            }
          }
          return null;
        });
  }

  /**
   * Quarantine only an isolated invalid item; the publisher splits rejected multi-player batches.
   */
  public CompletableFuture<Void> reject(List<Pending> batch, String category) {
    if (category == null || !category.matches("[A-Z0-9_]{1,64}"))
      throw new IllegalArgumentException("Invalid telemetry diagnostic category");
    List<Pending> sent = List.copyOf(batch);
    return database.write(
        connection -> {
          for (Pending pending : sent) {
            if (!matchesPending(connection, pending)) continue;
            deletePending(connection, pending);
            try (PreparedStatement sql =
                connection.prepareStatement(
                    "UPDATE telemetry_players SET needs_publish=CASE WHEN revision>? THEN 1 ELSE 0"
                        + " END,rejection_category=? WHERE server_id=? AND player_id=?")) {
              sql.setLong(1, pending.revision());
              sql.setString(2, category);
              sql.setString(3, serverId);
              sql.setString(4, pending.playerId().toString());
              sql.executeUpdate();
            }
          }
          return null;
        });
  }

  public static UUID sessionId(String serverId, UUID playerId, Instant joinedAt) {
    return UUID.nameUUIDFromBytes(
        ("MineplayTelemetrySession\n" + serverId + "\n" + playerId + "\n" + time(joinedAt))
            .getBytes(StandardCharsets.UTF_8));
  }

  private State state(Connection connection, String id) throws SQLException {
    try (PreparedStatement sql =
        connection.prepareStatement(
            "SELECT revision,observation_json,needs_publish FROM telemetry_players WHERE"
                + " server_id=? AND player_id=?")) {
      bindIdentity(sql, id);
      try (ResultSet row = sql.executeQuery()) {
        return row.next()
            ? new State(
                row.getLong(1),
                JsonParser.parseString(row.getString(2)).getAsJsonObject(),
                row.getInt(3) != 0)
            : null;
      }
    }
  }

  private List<String> candidates(Connection connection, int limit, Boolean online)
      throws SQLException {
    List<String> ids = new ArrayList<>();
    String lane =
        online == null
            ? ""
            : " AND json_extract(p.observation_json,'$.online')=" + (online ? 1 : 0);
    try (PreparedStatement sql =
        connection.prepareStatement(
            "SELECT p.player_id FROM telemetry_players p WHERE p.server_id=? AND p.needs_publish=1"
                + " AND NOT EXISTS(SELECT 1 FROM telemetry_pending q WHERE q.server_id=p.server_id"
                + " AND q.player_id=p.player_id)"
                + lane
                + " ORDER BY p.queue_order,p.rowid LIMIT ?")) {
      sql.setString(1, serverId);
      sql.setInt(2, limit);
      try (ResultSet rows = sql.executeQuery()) {
        while (rows.next()) ids.add(rows.getString(1));
      }
    }
    return ids;
  }

  private List<StoredSession> sessions(Connection connection, String id) throws SQLException {
    List<StoredSession> result = new ArrayList<>();
    try (PreparedStatement sql =
        connection.prepareStatement(
            "SELECT session_id,joined_at,left_at,changed_revision,acknowledged_revision FROM"
                + " telemetry_sessions WHERE server_id=? AND player_id=?")) {
      bindIdentity(sql, id);
      try (ResultSet rows = sql.executeQuery()) {
        while (rows.next())
          result.add(
              new StoredSession(
                  rows.getString(1),
                  rows.getString(2),
                  rows.getString(3),
                  rows.getLong(4),
                  rows.getLong(5)));
      }
    }
    return result;
  }

  private Map<String, String> addresses(Connection connection, String id) throws SQLException {
    Map<String, String> result = new HashMap<>();
    try (PreparedStatement sql =
        connection.prepareStatement(
            "SELECT address,last_seen_at FROM telemetry_addresses WHERE server_id=? AND"
                + " player_id=?")) {
      bindIdentity(sql, id);
      try (ResultSet rows = sql.executeQuery()) {
        while (rows.next()) result.put(rows.getString(1), rows.getString(2));
      }
    }
    return result;
  }

  private boolean matchesPending(Connection connection, Pending pending) throws SQLException {
    try (PreparedStatement sql =
        connection.prepareStatement(
            "SELECT snapshot_json FROM telemetry_pending WHERE server_id=? AND player_id=? AND"
                + " revision=?")) {
      bindIdentity(sql, pending.playerId().toString());
      sql.setLong(3, pending.revision());
      try (ResultSet row = sql.executeQuery()) {
        return row.next() && row.getString(1).equals(pending.json());
      }
    }
  }

  private void deletePending(Connection connection, Pending pending) throws SQLException {
    try (PreparedStatement sql =
        connection.prepareStatement(
            "DELETE FROM telemetry_pending WHERE server_id=? AND player_id=? AND revision=?")) {
      bindIdentity(sql, pending.playerId().toString());
      sql.setLong(3, pending.revision());
      sql.executeUpdate();
    }
  }

  private void bindIdentity(PreparedStatement sql, String id) throws SQLException {
    sql.setString(1, serverId);
    sql.setString(2, id);
  }

  private static String time(Instant instant) {
    return instant == null ? null : UTC.format(instant);
  }

  private static Instant instant(JsonObject json, String field) {
    return Instant.parse(json.get(field).getAsString());
  }

  private static String nullableString(JsonObject json, String field) {
    JsonElement value = json.get(field);
    return value == null || value.isJsonNull() ? null : value.getAsString();
  }

  private static Instant nullableInstant(JsonObject json, String field) {
    String value = nullableString(json, field);
    return value == null ? null : Instant.parse(value);
  }

  private static void earlier(JsonObject next, JsonObject old, String field) {
    if (instant(old, field).isBefore(instant(next, field))) next.add(field, old.get(field));
  }

  private static void laterNullable(JsonObject next, JsonObject old, String field) {
    Instant before = nullableInstant(old, field), after = nullableInstant(next, field);
    if (before != null && (after == null || before.isAfter(after))) next.add(field, old.get(field));
  }

  private static void mergeCounter(JsonObject next, JsonObject old, String field, long increment) {
    JsonElement previous = old.get(field), incoming = next.get(field);
    if (previous != null && !previous.isJsonNull()) {
      long value = previous.getAsLong();
      if (increment > 0 && value < Long.MAX_VALUE - 1)
        value += Math.min(increment, Long.MAX_VALUE - 1 - value);
      if (incoming == null || incoming.isJsonNull() || incoming.getAsLong() < value)
        next.addProperty(field, value);
    }
  }

  private static JsonObject base(TelemetryObservation o) {
    JsonObject json = new JsonObject();
    json.addProperty("playerUuid", o.playerId().toString());
    json.addProperty("playerName", o.playerName());
    json.addProperty("observedAt", time(o.observedAt()));
    json.addProperty("firstJoinedAt", time(o.firstJoinedAt()));
    json.addProperty("lastJoinedAt", time(o.lastJoinedAt()));
    json.addProperty("lastLeftAt", time(o.lastLeftAt()));
    json.addProperty("online", o.online());
    json.addProperty("totalPlaySeconds", o.totalPlaySeconds());
    json.addProperty("loginCount", o.loginCount());
    JsonObject economy = new JsonObject();
    economy.addProperty("status", o.economy().status().name());
    economy.addProperty("balance", o.economy().balance());
    economy.addProperty("provider", o.economy().provider());
    economy.addProperty("currency", o.economy().currency());
    json.add("economy", economy);
    return json;
  }
}
