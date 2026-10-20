package cloud.mineplay.punishments.communityservice;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.file.*;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlayerDataReceiptTest {
  @TempDir Path directory;

  private Path fixture(String compound, String receipt) throws IOException {
    Path path = directory.resolve("player.dat");
    try (DataOutputStream data =
        new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(path)))) {
      data.writeByte(10);
      data.writeUTF("");
      data.writeByte(3);
      data.writeUTF("DataVersion");
      data.writeInt(12345);
      data.writeByte(10);
      data.writeUTF(compound);
      data.writeByte(8);
      data.writeUTF("mineplaypunishments:restoration_receipt");
      data.writeUTF(receipt);
      data.writeByte(0);
      data.writeByte(0);
    }
    return path;
  }

  @Test
  void receiptConfirmsCurrentSavedPlayerFile() throws Exception {
    Path file = fixture("BukkitValues", "fresh-unique-receipt");
    assertDoesNotThrow(
        () ->
            PlayerDataReceipt.verify(
                file, "mineplaypunishments:restoration_receipt", "fresh-unique-receipt"));
  }

  @Test
  void staleReceiptDoesNotConfirmRestoration() throws Exception {
    Path file = fixture("BukkitValues", "old-receipt");
    assertThrows(
        IOException.class,
        () ->
            PlayerDataReceipt.verify(
                file, "mineplaypunishments:restoration_receipt", "new-receipt"));
  }

  @Test
  void matchingItemReceiptCannotMasqueradeAsPlayerReceipt() throws Exception {
    Path file = fixture("Inventory", "same-text");
    assertThrows(
        IOException.class,
        () ->
            PlayerDataReceipt.verify(file, "mineplaypunishments:restoration_receipt", "same-text"));
  }

  @Test
  void corruptOrMissingFileRetainsRecovery() throws Exception {
    assertThrows(
        IOException.class,
        () -> PlayerDataReceipt.verify(directory.resolve("missing.dat"), "key", "value"));
    Path corrupt = directory.resolve("corrupt.dat");
    Files.writeString(corrupt, "truncated data");
    assertThrows(IOException.class, () -> PlayerDataReceipt.verify(corrupt, "key", "value"));
  }

  @Test
  void truncatedCompressedTrailerCannotConfirmEvenWithMatchingReceipt() throws Exception {
    Path file = fixture("BukkitValues", "correct");
    byte[] complete = Files.readAllBytes(file);
    Files.write(file, java.util.Arrays.copyOf(complete, complete.length - 4));
    assertThrows(
        IOException.class,
        () -> PlayerDataReceipt.verify(file, "mineplaypunishments:restoration_receipt", "correct"));
  }
}
