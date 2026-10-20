package cloud.mineplay.punishments.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatabaseTest {
  @TempDir Path directory;

  @Test
  void migrationsAreRepeatableAndDurabilityPragmasAreEnabled() throws Exception {
    Path file = directory.resolve("nested/punishments.db");
    try (Database database = new Database(file)) {
      database.initialize();
      database.initialize();
      assertEquals("wal", scalar(database, "PRAGMA journal_mode"));
      assertEquals("1", scalar(database, "PRAGMA foreign_keys"));
      assertEquals("2", scalar(database, "PRAGMA synchronous"));
      assertEquals("1", scalar(database, "SELECT count(*) FROM schema_migrations"));
    }
    try (Database database = new Database(file)) {
      database.initialize();
      assertEquals("1", scalar(database, "SELECT count(*) FROM schema_migrations"));
    }
  }

  @Test
  void foreignKeysRejectOrphanedRecoveryRowsAndRollBack() throws Exception {
    try (Database database = new Database(directory.resolve("punishments.db"))) {
      database.initialize();
      assertThrows(
          CompletionException.class,
          () ->
              database
                  .write(
                      connection -> {
                        try (var sql = connection.createStatement()) {
                          sql.executeUpdate(
                              "INSERT INTO"
                                  + " community_service(id,punishment_id,target_id,required,tool,state)"
                                  + " VALUES"
                                  + " ('sentence','missing','player',10,'WOODEN_PICKAXE','ASSIGNED')");
                        }
                        return null;
                      })
                  .join());
      assertEquals("0", scalar(database, "SELECT count(*) FROM community_service"));
      assertEquals(1, database.failureEpoch());
    }
  }

  @Test
  void closedOrUninitializedDatabaseDoesNotSilentlyAcceptWork() throws Exception {
    Database database = new Database(directory.resolve("punishments.db"));
    assertThrows(CompletionException.class, () -> database.read(connection -> 1).join());
    database.initialize();
    database.close();
    assertThrows(CompletionException.class, () -> database.write(connection -> 1).join());
    assertFalse(database.available());
  }

  @Test
  void olderPluginRefusesAForwardDatabaseSchema() throws Exception {
    Path file = directory.resolve("punishments.db");
    try (Database database = new Database(file)) {
      database.initialize();
      database
          .write(
              connection -> {
                try (var sql = connection.createStatement()) {
                  sql.executeUpdate(
                      "INSERT INTO schema_migrations(version,applied_at) VALUES"
                          + " (2,'2026-10-18T12:00:00Z')");
                }
                return null;
              })
          .join();
    }
    try (Database olderPlugin = new Database(file)) {
      assertThrows(java.sql.SQLException.class, olderPlugin::initialize);
      assertFalse(olderPlugin.available());
    }
  }

  private String scalar(Database database, String query) {
    return database
        .read(
            connection -> {
              try (var sql = connection.createStatement();
                  var rows = sql.executeQuery(query)) {
                return rows.next() ? rows.getString(1) : null;
              }
            })
        .join();
  }
}
