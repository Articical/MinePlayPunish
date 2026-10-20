package cloud.mineplay.punishments.communityservice;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class SnapshotIntegrity {
  private SnapshotIntegrity() {}

  public static String checksum(String state) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(state.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  public static void verify(String state, String expected) {
    if (expected == null
        || !MessageDigest.isEqual(
            checksum(state).getBytes(StandardCharsets.US_ASCII),
            expected.getBytes(StandardCharsets.US_ASCII)))
      throw new IllegalStateException("Snapshot integrity check failed");
  }
}
