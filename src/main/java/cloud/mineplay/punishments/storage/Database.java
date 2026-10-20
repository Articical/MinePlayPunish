package cloud.mineplay.punishments.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** One connection and one worker; no live Bukkit objects may enter submitted work. */
public final class Database implements AutoCloseable {
  @FunctionalInterface
  public interface SqlWork<T> {
    T run(Connection connection) throws Exception;
  }

  private final Path path;
  private final ExecutorService executor =
      Executors.newSingleThreadExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "MineplayPunishments-SQLite");
            thread.setDaemon(true);
            return thread;
          });
  private final AtomicLong failureEpoch = new AtomicLong();
  private volatile Connection connection;
  private volatile boolean closed;

  public Database(Path path) {
    this.path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
  }

  /** Startup-only synchronous migration, before listeners or database jobs are registered. */
  public synchronized void initialize() throws SQLException, IOException {
    if (closed) throw new IllegalStateException("Database is closed");
    if (connection != null) return;
    Path parent = path.getParent();
    if (parent != null) Files.createDirectories(parent);
    // Paper's plugin class loader is not necessarily DriverManager's context class loader.
    try {
      Class.forName("org.sqlite.JDBC", true, Database.class.getClassLoader());
    } catch (ClassNotFoundException missingDriver) {
      throw new SQLException("Bundled SQLite JDBC driver is unavailable", missingDriver);
    }
    Connection opened = DriverManager.getConnection("jdbc:sqlite:" + path);
    try {
      try (Statement sql = opened.createStatement()) {
        sql.execute("PRAGMA busy_timeout=10000");
        try (ResultSet mode = sql.executeQuery("PRAGMA journal_mode=WAL")) {
          if (!mode.next() || !"wal".equalsIgnoreCase(mode.getString(1))) {
            throw new SQLException("SQLite WAL mode could not be enabled");
          }
        }
        sql.execute("PRAGMA synchronous=FULL");
        sql.execute("PRAGMA foreign_keys=ON");
        sql.execute("PRAGMA trusted_schema=OFF");
        sql.execute(
            "CREATE TABLE IF NOT EXISTS schema_migrations (version INTEGER PRIMARY KEY, applied_at"
                + " TEXT NOT NULL)");
        try (ResultSet versions = sql.executeQuery("SELECT MAX(version) FROM schema_migrations")) {
          if (versions.next() && versions.getInt(1) > 1)
            throw new SQLException(
                "Database schema is newer than this plugin; use a compatible release");
        }
      }
      applyMigration(opened, 1, "/db/V001__core.sql");
      try (Statement sql = opened.createStatement();
          ResultSet rows = sql.executeQuery("PRAGMA quick_check")) {
        if (!rows.next() || !"ok".equals(rows.getString(1))) {
          throw new SQLException("SQLite integrity check failed");
        }
      }
      try (Statement sql = opened.createStatement();
          ResultSet rows = sql.executeQuery("PRAGMA foreign_key_check")) {
        if (rows.next()) throw new SQLException("SQLite foreign key integrity check failed");
      }
      connection = opened;
    } catch (SQLException | IOException | RuntimeException failure) {
      failureEpoch.incrementAndGet();
      try {
        opened.close();
      } catch (SQLException closing) {
        failure.addSuppressed(closing);
      }
      throw failure;
    }
  }

  private static void applyMigration(Connection connection, int version, String resource)
      throws SQLException, IOException {
    try (var query =
        connection.prepareStatement("SELECT 1 FROM schema_migrations WHERE version=?")) {
      query.setInt(1, version);
      try (ResultSet result = query.executeQuery()) {
        if (result.next()) return;
      }
    }
    String source;
    try (InputStream input = Database.class.getResourceAsStream(resource)) {
      if (input == null) throw new IOException("Missing database migration " + resource);
      source = new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
    connection.setAutoCommit(false);
    try {
      // Migration resources contain DDL only, with no trigger bodies or quoted semicolons.
      for (String statement : source.split(";")) {
        if (!statement.isBlank())
          try (Statement sql = connection.createStatement()) {
            sql.execute(statement);
          }
      }
      try (var insert =
          connection.prepareStatement(
              "INSERT INTO schema_migrations(version,applied_at) VALUES (?,?)")) {
        insert.setInt(1, version);
        insert.setString(2, java.time.Instant.now().toString());
        insert.executeUpdate();
      }
      connection.commit();
    } catch (SQLException failure) {
      try {
        connection.rollback();
      } catch (SQLException rollback) {
        failure.addSuppressed(rollback);
      }
      throw failure;
    } finally {
      connection.setAutoCommit(true);
    }
  }

  public <T> CompletableFuture<T> read(SqlWork<T> work) {
    return submit(work, false);
  }

  /**
   * Completes only after a successful COMMIT. Any work failure rolls back the whole transaction.
   */
  public <T> CompletableFuture<T> write(SqlWork<T> work) {
    return submit(work, true);
  }

  private <T> CompletableFuture<T> submit(SqlWork<T> work, boolean transactional) {
    Objects.requireNonNull(work, "work");
    CompletableFuture<T> future = new CompletableFuture<>();
    if (closed || connection == null) {
      failureEpoch.incrementAndGet();
      future.completeExceptionally(new IllegalStateException("Database is unavailable"));
      return future;
    }
    try {
      executor.execute(
          () -> {
            Connection current = connection;
            try {
              if (current == null || current.isClosed())
                throw new SQLException("Database is unavailable");
              if (transactional) current.setAutoCommit(false);
              T result = work.run(current);
              if (transactional) current.commit();
              if (transactional) current.setAutoCommit(true);
              future.complete(result);
            } catch (Throwable failure) {
              // Fail closed even on a non-SQL exception: a partly completed database job is
              // unverified.
              failureEpoch.incrementAndGet();
              if (transactional && current != null) {
                try {
                  current.rollback();
                } catch (SQLException rollback) {
                  failure.addSuppressed(rollback);
                }
                try {
                  current.setAutoCommit(true);
                } catch (SQLException reset) {
                  failure.addSuppressed(reset);
                }
              }
              future.completeExceptionally(failure);
            }
          });
    } catch (RejectedExecutionException failure) {
      failureEpoch.incrementAndGet();
      future.completeExceptionally(failure);
    }
    return future;
  }

  /** A cache must be reloaded after this changes; successful unrelated writes cannot clear it. */
  public long failureEpoch() {
    return failureEpoch.get();
  }

  public boolean available() {
    return !closed && connection != null;
  }

  public Path path() {
    return path;
  }

  @Override
  public synchronized void close() throws SQLException {
    if (closed) return;
    closed = true;
    executor.shutdown();
    boolean stopped;
    try {
      stopped = executor.awaitTermination(30, TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new SQLException("Interrupted while draining SQLite writes", interrupted);
    }
    if (!stopped)
      throw new SQLException(
          "SQLite writes have not drained; connection retained for worker safety");
    Connection current = connection;
    connection = null;
    if (current != null) current.close();
  }
}
