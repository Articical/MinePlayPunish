package cloud.mineplay.punishments.communityservice;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import org.bukkit.Server;

/**
 * Small bounded NBT reader: verifies only the root BukkitValues receipt, never executes or
 * deserializes items.
 */
final class PlayerDataReceipt {
  private static final int MAX_BYTES = 32 * 1024 * 1024, MAX_ELEMENTS = 1_000_000, MAX_DEPTH = 64;

  private PlayerDataReceipt() {}

  static Path playerFile(Server server, UUID id) {
    // Paper 26.3 stores player data under the level root, separately from dimensions.
    return server.getLevelDirectory().resolve("players").resolve("data").resolve(id + ".dat");
  }

  static void verify(Path file, String namespacedKey, String receipt) throws IOException {
    if (!Files.isRegularFile(file)) throw new IOException("Saved player file missing");
    try (InputStream compressed = Files.newInputStream(file);
        GZIPInputStream gzip = new GZIPInputStream(compressed);
        DataInputStream input = new DataInputStream(new BoundedInput(gzip, MAX_BYTES))) {
      if (input.readUnsignedByte() != 10)
        throw new IOException("Player file has no NBT root compound");
      input.readUTF();
      Holder holder = new Holder(namespacedKey, receipt);
      compound(input, 0, "", holder);
      // Reading through EOF validates the gzip trailer/CRC; closing early does not.
      if (input.read() != -1) throw new IOException("Trailing NBT data in saved player file");
      if (!holder.found) throw new IOException("Saved player restoration receipt not found");
    }
  }

  private static final class Holder {
    final String key, receipt;
    boolean found;

    Holder(String key, String receipt) {
      this.key = key;
      this.receipt = receipt;
    }
  }

  private static void compound(DataInputStream in, int depth, String path, Holder receipt)
      throws IOException {
    if (depth > MAX_DEPTH) throw new IOException("NBT nesting limit exceeded");
    for (int count = 0; count < MAX_ELEMENTS; count++) {
      int type = in.readUnsignedByte();
      if (type == 0) return;
      String name = in.readUTF();
      String child = path.isEmpty() ? name : path + "/" + name;
      if (type == 8 && path.equals("BukkitValues") && name.equals(receipt.key)) {
        String value = in.readUTF();
        if (!value.equals(receipt.receipt))
          throw new IOException("Saved player restoration receipt differs");
        receipt.found = true;
      } else payload(in, type, depth + 1, child, receipt);
    }
    throw new IOException("NBT element limit exceeded");
  }

  private static void payload(DataInputStream in, int type, int depth, String path, Holder receipt)
      throws IOException {
    if (depth > MAX_DEPTH) throw new IOException("NBT nesting limit exceeded");
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
        if (child == 0 && size != 0) throw new IOException("Invalid NBT list");
        for (int i = 0; i < size; i++) payload(in, child, depth + 1, path + "[]", receipt);
      }
      case 10 -> compound(in, depth, path, receipt);
      case 11 -> in.skipNBytes((long) length(in) * 4);
      case 12 -> in.skipNBytes((long) length(in) * 8);
      default -> throw new IOException("Unknown NBT tag");
    }
  }

  private static int length(DataInputStream in) throws IOException {
    int value = in.readInt();
    if (value < 0 || value > MAX_ELEMENTS) throw new IOException("NBT array limit exceeded");
    return value;
  }

  private static final class BoundedInput extends FilterInputStream {
    long remaining;

    BoundedInput(InputStream input, long maximum) {
      super(input);
      remaining = maximum;
    }

    @Override
    public int read() throws IOException {
      if (remaining-- <= 0) throw new IOException("NBT file limit exceeded");
      return super.read();
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
      if (remaining <= 0) throw new IOException("NBT file limit exceeded");
      int count = in.read(bytes, offset, (int) Math.min(length, remaining));
      if (count > 0) remaining -= count;
      return count;
    }

    @Override
    public long skip(long amount) throws IOException {
      if (remaining <= 0) throw new IOException("NBT file limit exceeded");
      long count = in.skip(Math.min(amount, remaining));
      remaining -= count;
      return count;
    }
  }
}
