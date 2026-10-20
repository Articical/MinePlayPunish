package cloud.mineplay.punishments.communityservice;

import cloud.mineplay.punishments.punishment.Models.*;
import cloud.mineplay.punishments.punishment.PunishmentService;
import cloud.mineplay.punishments.storage.Database;
import com.google.gson.JsonObject;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;

final class ServiceRepository {
  record Snapshot(
      String id, UUID sentence, UUID player, String json, String checksum, String state) {
    void verify() {
      SnapshotIntegrity.verify(json, checksum);
    }
  }

  record Position(UUID world, int x, int y, int z) {}

  record Regeneration(Position position, String material, long due) {}

  record Credit(boolean accepted, int completed, int required) {}

  private final Database database;
  private final PunishmentService service;

  ServiceRepository(Database database, PunishmentService service) {
    this.database = database;
    this.service = service;
  }

  CompletableFuture<Void> initialize() {
    return database.write(
        c -> {
          try (Statement s = c.createStatement()) {
            s.executeUpdate(
                "CREATE TABLE IF NOT EXISTS player_snapshots (id TEXT PRIMARY KEY, player_id TEXT"
                    + " NOT NULL, sentence_id TEXT NOT NULL UNIQUE REFERENCES"
                    + " community_service(id), state_json TEXT NOT NULL, checksum TEXT NOT NULL,"
                    + " recovery_status TEXT NOT NULL, created_at TEXT NOT NULL, restored_at"
                    + " TEXT)");
            s.executeUpdate(
                "CREATE INDEX IF NOT EXISTS snapshots_player ON"
                    + " player_snapshots(player_id,recovery_status)");
            s.executeUpdate(
                "CREATE TABLE IF NOT EXISTS quarry_regeneration (world_id TEXT NOT NULL,x INTEGER"
                    + " NOT NULL,y INTEGER NOT NULL,z INTEGER NOT NULL,material TEXT NOT"
                    + " NULL,due_at INTEGER NOT NULL,PRIMARY KEY(world_id,x,y,z))");
            s.executeUpdate(
                "CREATE INDEX IF NOT EXISTS regeneration_due ON quarry_regeneration(due_at)");
          }
          return null;
        });
  }

  CompletableFuture<Optional<Snapshot>> snapshot(UUID sentence) {
    return database.read(c -> snapshot(c, sentence));
  }

  private Optional<Snapshot> snapshot(Connection c, UUID sentence) throws SQLException {
    try (PreparedStatement q =
        c.prepareStatement("SELECT * FROM player_snapshots WHERE sentence_id=?")) {
      q.setString(1, sentence.toString());
      try (ResultSet r = q.executeQuery()) {
        return r.next()
            ? Optional.of(
                new Snapshot(
                    r.getString("id"),
                    sentence,
                    UUID.fromString(r.getString("player_id")),
                    r.getString("state_json"),
                    r.getString("checksum"),
                    r.getString("recovery_status")))
            : Optional.empty();
      }
    }
  }

  CompletableFuture<Snapshot> capture(Sentence sentence, String json) {
    String checksum = SnapshotIntegrity.checksum(json);
    return database.write(
        c -> {
          Optional<Snapshot> previous = snapshot(c, sentence.id());
          if (previous.isPresent()) return previous.get();
          try (PreparedStatement q =
              c.prepareStatement(
                  "UPDATE community_service SET state='SNAPSHOTTED' WHERE id=? AND"
                      + " state='ASSIGNED'")) {
            q.setString(1, sentence.id().toString());
            if (q.executeUpdate() != 1)
              throw new IllegalStateException("Sentence changed before snapshot");
          }
          String id = UUID.randomUUID().toString();
          try (PreparedStatement q =
              c.prepareStatement(
                  "INSERT INTO"
                      + " player_snapshots(id,player_id,sentence_id,state_json,checksum,recovery_status,created_at)"
                      + " VALUES(?,?,?,?,?,'SNAPSHOTTED',?)")) {
            q.setString(1, id);
            q.setString(2, sentence.targetId().toString());
            q.setString(3, sentence.id().toString());
            q.setString(4, json);
            q.setString(5, checksum);
            q.setString(6, Instant.now().toString());
            q.executeUpdate();
          }
          event(
              c,
              sentence.punishmentId(),
              "community_service.snapshot_created",
              "SYSTEM",
              new JsonObject());
          return new Snapshot(
              id, sentence.id(), sentence.targetId(), json, checksum, "SNAPSHOTTED");
        });
  }

  CompletableFuture<Boolean> activate(Sentence sentence) {
    return database.write(
        c -> {
          if (state(c, sentence.id()).equals("ACTIVE")) return true;
          try (PreparedStatement q =
              c.prepareStatement(
                  "UPDATE community_service SET state='ACTIVE',started_at=COALESCE(started_at,?)"
                      + " WHERE id=? AND state='SNAPSHOTTED'")) {
            q.setString(1, Instant.now().toString());
            q.setString(2, sentence.id().toString());
            if (q.executeUpdate() != 1) return false;
          }
          snapshotState(c, sentence.id(), "ACTIVE");
          event(
              c, sentence.punishmentId(), "community_service.started", "SYSTEM", new JsonObject());
          return true;
        });
  }

  CompletableFuture<Boolean> beginRestore(Sentence sentence) {
    return database.write(
        c -> {
          if (state(c, sentence.id()).equals("RESTORING")) return true;
          try (PreparedStatement q =
              c.prepareStatement(
                  "UPDATE community_service SET state='RESTORING' WHERE id=? AND"
                      + " state='WORK_COMPLETE'")) {
            q.setString(1, sentence.id().toString());
            if (q.executeUpdate() != 1) return false;
          }
          snapshotState(c, sentence.id(), "RESTORING");
          event(
              c,
              sentence.punishmentId(),
              "community_service.restoring",
              "SYSTEM",
              new JsonObject());
          return true;
        });
  }

  CompletableFuture<Void> recovery(Sentence sentence) {
    return database.write(
        c -> {
          try (PreparedStatement q =
              c.prepareStatement(
                  "UPDATE community_service SET state='RECOVERY' WHERE id=? AND"
                      + " state NOT IN ('COMPLETED','RECOVERY')")) {
            q.setString(1, sentence.id().toString());
            if (q.executeUpdate() != 1) return null;
          }
          snapshotState(c, sentence.id(), "RECOVERY");
          event(
              c,
              sentence.punishmentId(),
              "community_service.recovery_required",
              "SYSTEM",
              new JsonObject());
          return null;
        });
  }

  CompletableFuture<Void> emergencyRelease(Sentence sentence, String actor) {
    return database.write(
        c -> {
          try (PreparedStatement q =
              c.prepareStatement(
                  "UPDATE community_service SET state='WORK_COMPLETE' WHERE id=? AND"
                      + " state NOT IN ('COMPLETED','WORK_COMPLETE','RESTORING')")) {
            q.setString(1, sentence.id().toString());
            if (q.executeUpdate() != 1) return null;
          }
          event(
              c,
              sentence.punishmentId(),
              "community_service.recovery_requested",
              actor,
              new JsonObject());
          return null;
        });
  }

  CompletableFuture<Void> complete(Sentence sentence, boolean restored) {
    return database.write(
        c -> {
          // Repeated completion callbacks must not invalidate an already confirmed release.
          if (state(c, sentence.id()).equals("COMPLETED")) return null;
          if (!restored && snapshot(c, sentence.id()).isPresent())
            throw new IllegalStateException("Snapshot must be restored");
          try (PreparedStatement q =
              c.prepareStatement(
                  "UPDATE community_service SET state='COMPLETED',completed_at=? WHERE id=? AND"
                      + " state IN ('WORK_COMPLETE','RESTORING')")) {
            q.setString(1, Instant.now().toString());
            q.setString(2, sentence.id().toString());
            if (q.executeUpdate() != 1)
              throw new IllegalStateException("Sentence changed during restoration");
          }
          try (PreparedStatement q =
              c.prepareStatement(
                  "UPDATE player_snapshots SET recovery_status='CONFIRMED',restored_at=? WHERE"
                      + " sentence_id=?")) {
            q.setString(1, Instant.now().toString());
            q.setString(2, sentence.id().toString());
            q.executeUpdate();
          }
          try (PreparedStatement q =
              c.prepareStatement(
                  "UPDATE punishments SET status='COMPLETED' WHERE id=? AND status='ACTIVE'")) {
            q.setString(1, sentence.punishmentId().toString());
            q.executeUpdate();
          }
          event(
              c,
              sentence.punishmentId(),
              "community_service.completed",
              "SYSTEM",
              new JsonObject());
          return null;
        });
  }

  CompletableFuture<Credit> credit(Sentence sentence, Regeneration regeneration) {
    return database.write(
        c -> {
          int completed, required;
          try (PreparedStatement q =
              c.prepareStatement(
                  "SELECT completed,required,state FROM community_service WHERE id=?")) {
            q.setString(1, sentence.id().toString());
            try (ResultSet r = q.executeQuery()) {
              if (!r.next() || !r.getString("state").equals("ACTIVE"))
                return new Credit(false, 0, 0);
              completed = r.getInt("completed");
              required = r.getInt("required");
            }
          }
          try (PreparedStatement q =
              c.prepareStatement(
                  "INSERT OR IGNORE INTO quarry_regeneration(world_id,x,y,z,material,due_at)"
                      + " VALUES(?,?,?,?,?,?)")) {
            bind(q, regeneration.position);
            q.setString(5, regeneration.material);
            q.setLong(6, regeneration.due);
            if (q.executeUpdate() != 1) return new Credit(false, completed, required);
          }
          completed++;
          try (PreparedStatement q =
              c.prepareStatement("UPDATE community_service SET completed=?,state=? WHERE id=?")) {
            q.setInt(1, completed);
            q.setString(2, completed >= required ? "WORK_COMPLETE" : "ACTIVE");
            q.setString(3, sentence.id().toString());
            q.executeUpdate();
          }
          JsonObject payload = new JsonObject();
          payload.addProperty("completed", completed);
          payload.addProperty("required", required);
          event(
              c,
              sentence.punishmentId(),
              "community_service.progress",
              sentence.targetId().toString(),
              payload);
          return new Credit(true, completed, required);
        });
  }

  CompletableFuture<List<Regeneration>> regenerations() {
    return database.read(
        c -> {
          List<Regeneration> rows = new ArrayList<>();
          try (Statement q = c.createStatement();
              ResultSet r = q.executeQuery("SELECT * FROM quarry_regeneration")) {
            while (r.next())
              rows.add(
                  new Regeneration(
                      new Position(
                          UUID.fromString(r.getString("world_id")),
                          r.getInt("x"),
                          r.getInt("y"),
                          r.getInt("z")),
                      r.getString("material"),
                      r.getLong("due_at")));
          }
          return rows;
        });
  }

  CompletableFuture<Void> finishRegeneration(Position position) {
    return database.write(
        c -> {
          try (PreparedStatement q =
              c.prepareStatement(
                  "DELETE FROM quarry_regeneration WHERE world_id=? AND x=? AND y=? AND z=?")) {
            bind(q, position);
            q.executeUpdate();
          }
          return null;
        });
  }

  private static void bind(PreparedStatement q, Position p) throws SQLException {
    q.setString(1, p.world.toString());
    q.setInt(2, p.x);
    q.setInt(3, p.y);
    q.setInt(4, p.z);
  }

  private static void snapshotState(Connection c, UUID sentence, String state) throws SQLException {
    try (PreparedStatement q =
        c.prepareStatement("UPDATE player_snapshots SET recovery_status=? WHERE sentence_id=?")) {
      q.setString(1, state);
      q.setString(2, sentence.toString());
      q.executeUpdate();
    }
  }

  private static String state(Connection c, UUID sentence) throws SQLException {
    try (PreparedStatement q =
        c.prepareStatement("SELECT state FROM community_service WHERE id=?")) {
      q.setString(1, sentence.toString());
      try (ResultSet r = q.executeQuery()) {
        return r.next() ? r.getString(1) : "MISSING";
      }
    }
  }

  private void event(Connection c, UUID id, String type, String actor, JsonObject payload)
      throws SQLException {
    try (PreparedStatement q =
        c.prepareStatement("UPDATE punishments SET revision=revision+1 WHERE id=?")) {
      q.setString(1, id.toString());
      q.executeUpdate();
    }
    try (PreparedStatement q = c.prepareStatement("SELECT * FROM punishments WHERE id=?")) {
      q.setString(1, id.toString());
      try (ResultSet r = q.executeQuery()) {
        if (!r.next()) throw new SQLException("Missing parent punishment");
        String expires = r.getString("expires_at");
        Punishment p =
            new Punishment(
                id,
                r.getString("reference"),
                UUID.fromString(r.getString("target_id")),
                r.getString("target_name"),
                Type.valueOf(r.getString("type")),
                r.getString("reason"),
                r.getString("issuer"),
                Source.valueOf(r.getString("source")),
                Instant.parse(r.getString("issued_at")),
                expires == null ? null : Instant.parse(expires),
                Status.valueOf(r.getString("status")),
                r.getLong("revision"),
                r.getString("appeal_url"));
        service.emit(c, p, type, actor, payload);
      }
    }
  }
}
