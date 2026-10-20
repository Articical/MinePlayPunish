package cloud.mineplay.punishments.communityservice;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class QuarrySafetyTest {
  @Test
  void cornersAreInclusiveAndReversible() {
    QuarryBounds bounds = QuarryBounds.between(10, 70, 10, -2, 65, -2);
    assertEquals(13L * 6 * 13, bounds.volume());
    assertTrue(bounds.contains(-2, 65, -2));
    assertTrue(bounds.contains(10, 70, 10));
    assertFalse(bounds.contains(11, 70, 10));
    assertFalse(bounds.contains(0, 64, 0));
    assertTrue(bounds.containsPrisoner(10.8, 74, 10.8));
    assertFalse(bounds.containsPrisoner(11, 74, 10));
  }

  @Test
  void malformedAndOverflowingVolumesAreRejected() {
    assertThrows(IllegalArgumentException.class, () -> new QuarryBounds(1, 0, 0, 0, 1, 1));
    assertThrows(
        ArithmeticException.class,
        () ->
            new QuarryBounds(
                    Integer.MIN_VALUE,
                    Integer.MIN_VALUE,
                    Integer.MIN_VALUE,
                    Integer.MAX_VALUE,
                    Integer.MAX_VALUE,
                    Integer.MAX_VALUE)
                .volume());
  }

  @Test
  void everyMiningRequirementIsNecessary() {
    assertTrue(MiningRules.eligible(true, true, true, true, true, true, false));
    for (int failed = 0; failed < 6; failed++) {
      boolean[] criteria = {true, true, true, true, true, true};
      criteria[failed] = false;
      assertFalse(
          MiningRules.eligible(
              criteria[0], criteria[1], criteria[2], criteria[3], criteria[4], criteria[5], false));
    }
    assertFalse(MiningRules.eligible(true, true, true, true, true, true, true));
  }

  @Test
  void lateCancellationAndWorldOutcomePreventCredit() {
    assertTrue(MiningRules.finalised(false, true, true, true));
    assertFalse(MiningRules.finalised(true, true, true, true));
    assertFalse(MiningRules.finalised(false, false, true, true));
    assertFalse(MiningRules.finalised(false, true, false, true));
    assertFalse(MiningRules.finalised(false, true, true, false));
  }

  @Test
  void changedSnapshotCannotBeRestored() {
    String snapshot = "{\"inventory\":[\"opaque bytes\"]}";
    String digest = SnapshotIntegrity.checksum(snapshot);
    assertDoesNotThrow(() -> SnapshotIntegrity.verify(snapshot, digest));
    assertThrows(
        IllegalStateException.class, () -> SnapshotIntegrity.verify(snapshot + " ", digest));
    assertThrows(IllegalStateException.class, () -> SnapshotIntegrity.verify(snapshot, null));
  }
}
