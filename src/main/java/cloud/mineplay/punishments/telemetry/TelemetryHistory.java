package cloud.mineplay.punishments.telemetry;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

/** Reads only explicit historical fields; Paper's API can substitute file mtime when absent. */
final class TelemetryHistory {
  private static final int MAX_BYTES = 32 * 1024 * 1024, MAX_ELEMENTS = 1_000_000;

  record PlayerHistory(
      UUID playerId, String lastKnownName, Instant firstJoin, Instant lastJoin, Long playSeconds) {}

  private TelemetryHistory() {}

  static PlayerHistory read(Path level, UUID id) throws IOException {
    Path file = level.resolve("players/data").resolve(id + ".dat");
    if (!Files.isRegularFile(file) || Files.size(file) > MAX_BYTES) return null;
    Fields fields = new Fields();
    try (InputStream raw = Files.newInputStream(file);
        GZIPInputStream gzip = new GZIPInputStream(raw);
        DataInputStream input = new DataInputStream(new LimitedInput(gzip, MAX_BYTES))) {
      if (input.readUnsignedByte() != 10) throw new IOException("Invalid player NBT root");
      input.readUTF();
      compound(input, 0, "", fields);
      if (input.read() != -1) throw new IOException("Trailing player NBT data");
    }
    if (fields.first == null || fields.first <= 0) return null;
    Instant last =
        fields.last == null || fields.last < fields.first
            ? null
            : Instant.ofEpochMilli(fields.last);
    return new PlayerHistory(
        id,
        fields.name,
        Instant.ofEpochMilli(fields.first),
        last,
        playSeconds(level.resolve("players/stats").resolve(id + ".json")));
  }

  private static final class Fields {
    Long first, last;
    String name;
    int remaining = MAX_ELEMENTS;
  }

  private static void compound(DataInputStream in, int depth, String path, Fields fields)
      throws IOException {
    if (depth > 64) throw new IOException("Player NBT nesting limit");
    while (fields.remaining-- > 0) {
      int type = in.readUnsignedByte();
      if (type == 0) return;
      String name = in.readUTF();
      String child = path.isEmpty() ? name : path + "/" + name;
      if (type == 4 && child.equals("bukkit/firstPlayed")) {
        if (fields.first != null) throw new IOException("Duplicate player history field");
        fields.first = in.readLong();
      } else if (type == 4 && child.equals("Paper/LastLogin")) {
        if (fields.last != null) throw new IOException("Duplicate player history field");
        fields.last = in.readLong();
      } else if (type == 8 && child.equals("bukkit/lastKnownName")) {
        if (fields.name != null) throw new IOException("Duplicate player history field");
        fields.name = in.readUTF();
      } else payload(in, type, depth + 1, child, fields);
    }
    throw new IOException("Player NBT element limit");
  }

  private static void payload(DataInputStream in, int type, int depth, String path, Fields fields)
      throws IOException {
    if (depth > 64 || fields.remaining-- <= 0) throw new IOException("Player NBT limit");
    switch (type) {
      case 1 -> in.readByte();
      case 2 -> in.readShort();
      case 3 -> in.readInt();
      case 4 -> in.readLong();
      case 5 -> in.readFloat();
      case 6 -> in.readDouble();
      case 7 -> in.skipNBytes(length(in));
      case 8 -> in.readUTF();
      case 9 -> {
        int child = in.readUnsignedByte(), size = length(in);
        if (child == 0 && size != 0) throw new IOException("Invalid player NBT list");
        for (int i = 0; i < size; i++) payload(in, child, depth + 1, path + "[]", fields);
      }
      case 10 -> compound(in, depth, path, fields);
      case 11 -> in.skipNBytes((long) length(in) * 4);
      case 12 -> in.skipNBytes((long) length(in) * 8);
      default -> throw new IOException("Invalid player NBT tag");
    }
  }

  private static int length(DataInputStream input) throws IOException {
    int count = input.readInt();
    if (count < 0 || count > MAX_ELEMENTS) throw new IOException("Player NBT array limit");
    return count;
  }

  /** Absence of the statistic is unknown, not a newly manufactured zero. */
  static Long playSeconds(Path file) {
    if (!Files.isRegularFile(file)) return null;
    try (InputStream input = Files.newInputStream(file)) {
      byte[] bytes = input.readNBytes(1024 * 1024 + 1);
      if (bytes.length > 1024 * 1024) return null;
      try (JsonReader json =
          new JsonReader(new StringReader(new String(bytes, StandardCharsets.UTF_8)))) {
        json.setStrictness(com.google.gson.Strictness.STRICT);
        Long ticks = null;
        json.beginObject();
        while (json.hasNext()) {
          if (!json.nextName().equals("stats") || json.peek() != JsonToken.BEGIN_OBJECT) {
            json.skipValue();
            continue;
          }
          json.beginObject();
          while (json.hasNext()) {
            if (!json.nextName().equals("minecraft:custom")
                || json.peek() != JsonToken.BEGIN_OBJECT) {
              json.skipValue();
              continue;
            }
            json.beginObject();
            while (json.hasNext()) {
              if (json.nextName().equals("minecraft:play_time")) {
                if (ticks != null || json.peek() != JsonToken.NUMBER) return null;
                ticks = new java.math.BigDecimal(json.nextString()).longValueExact();
                if (ticks < 0 || ticks >= Long.MAX_VALUE) return null;
              } else json.skipValue();
            }
            json.endObject();
          }
          json.endObject();
        }
        json.endObject();
        if (json.peek() != JsonToken.END_DOCUMENT) return null;
        return ticks == null ? null : ticks / 20;
      }
    } catch (IOException | RuntimeException failure) {
      return null;
    }
  }

  private static final class LimitedInput extends FilterInputStream {
    private long remaining;

    LimitedInput(InputStream input, long maximum) {
      super(input);
      remaining = maximum;
    }

    @Override
    public int read() throws IOException {
      if (remaining-- <= 0) throw new IOException("Player NBT byte limit");
      return super.read();
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
      if (remaining <= 0) throw new IOException("Player NBT byte limit");
      int count = in.read(bytes, offset, (int) Math.min(length, remaining));
      if (count > 0) remaining -= count;
      return count;
    }

    @Override
    public long skip(long amount) throws IOException {
      if (remaining <= 0) throw new IOException("Player NBT byte limit");
      long count = in.skip(Math.min(amount, remaining));
      remaining -= count;
      return count;
    }
  }
}
