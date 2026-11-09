package cloud.mineplay.punishments.telemetry;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TelemetryHistoryTest {
  @TempDir Path level;
  private final UUID id = UUID.randomUUID();
  private static final long FIRST = 1700000000000L, LAST = 1700100000000L;

  @Test
  void readsExplicitHistoryAndExistingPlayTimeWithoutInventingLogout() throws Exception {
    writePlayer(level, id, FIRST, LAST, ".Bedrock Player");
    Path stats = level.resolve("players/stats");
    Files.createDirectories(stats);
    Files.writeString(
        stats.resolve(id + ".json"),
        "{\"stats\":{\"minecraft:custom\":{\"minecraft:play_time\":12345}}}");
    var result = TelemetryHistory.read(level, id);
    assertEquals(Instant.ofEpochMilli(FIRST), result.firstJoin());
    assertEquals(Instant.ofEpochMilli(LAST), result.lastJoin());
    assertEquals(".Bedrock Player", result.lastKnownName());
    assertEquals(617L, result.playSeconds());
  }

  @Test
  void missingFirstJoinNeverUsesFileModificationTime() throws Exception {
    Path file = writePlayer(level, id, null, LAST, "Player");
    Files.setLastModifiedTime(file, FileTime.fromMillis(FIRST));
    assertNull(TelemetryHistory.read(level, id));
  }

  @Test
  void missingLastLoginRetainsOnlyKnownFirstForLiveCollection() throws Exception {
    Path file = writePlayer(level, id, FIRST, null, "Player");
    Files.setLastModifiedTime(file, FileTime.fromMillis(LAST));
    var history = TelemetryHistory.read(level, id);
    assertEquals(Instant.ofEpochMilli(FIRST), history.firstJoin());
    assertNull(history.lastJoin());
  }

  @Test
  void missingPlayerDataIsUnknown() throws Exception {
    assertNull(TelemetryHistory.read(level, id));
  }

  @Test
  void missingStatisticsAreUnknown() throws Exception {
    writePlayer(level, id, FIRST, LAST, "Player");
    assertNull(TelemetryHistory.read(level, id).playSeconds());
  }

  @Test
  void corruptGzipDoesNotYieldPartialHistory() throws Exception {
    Path file = writePlayer(level, id, FIRST, LAST, "Player");
    byte[] bytes = Files.readAllBytes(file);
    bytes[bytes.length - 6] ^= 0x7f;
    Files.write(file, bytes);
    assertThrows(IOException.class, () -> TelemetryHistory.read(level, id));
  }

  @ParameterizedTest
  @ValueSource(longs = {-1, 0})
  void nonpositiveFirstJoinIsUnknown(long value) throws Exception {
    writePlayer(level, id, value, LAST, "Player");
    assertNull(TelemetryHistory.read(level, id));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{}",
        "{\"stats\":{}}",
        "{\"stats\":{\"minecraft:custom\":{}}}",
        "invalid",
        "{\"stats\":{\"minecraft:custom\":{\"minecraft:play_time\":-1}}}",
        "{\"stats\":{\"minecraft:custom\":{\"minecraft:play_time\":1.5}}}",
        "{\"stats\":{\"minecraft:custom\":{\"minecraft:play_time\":\"20\"}}}"
      })
  void missingOrInvalidStatisticIsNullRatherThanZero(String json) throws Exception {
    Path file = level.resolve("stats.json");
    Files.writeString(file, json);
    assertNull(TelemetryHistory.playSeconds(file));
  }

  @Test
  void explicitZeroStatisticRemainsZero() throws Exception {
    Path file = level.resolve("stats.json");
    Files.writeString(file, "{\"stats\":{\"minecraft:custom\":{\"minecraft:play_time\":0}}}");
    assertEquals(0L, TelemetryHistory.playSeconds(file));
  }

  @Test
  void oversizedStatisticsAreRefused() throws Exception {
    Path file = level.resolve("stats.json");
    Files.writeString(file, " ".repeat(1024 * 1024 + 1));
    assertNull(TelemetryHistory.playSeconds(file));
  }

  static Path writePlayer(Path level, UUID id, Long first, Long last, String name)
      throws Exception {
    Path data = level.resolve("players/data");
    Files.createDirectories(data);
    Path file = data.resolve(id + ".dat");
    try (DataOutputStream out =
        new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(file)))) {
      out.writeByte(10);
      out.writeUTF("");
      out.writeByte(10);
      out.writeUTF("bukkit");
      if (first != null) {
        out.writeByte(4);
        out.writeUTF("firstPlayed");
        out.writeLong(first);
      }
      if (name != null) {
        out.writeByte(8);
        out.writeUTF("lastKnownName");
        out.writeUTF(name);
      }
      out.writeByte(0);
      out.writeByte(10);
      out.writeUTF("Paper");
      if (last != null) {
        out.writeByte(4);
        out.writeUTF("LastLogin");
        out.writeLong(last);
      }
      out.writeByte(4);
      out.writeUTF("LastSeen");
      out.writeLong(LAST + 5000);
      out.writeByte(0);
      out.writeByte(0);
    }
    return file;
  }
}
