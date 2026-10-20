package cloud.mineplay.punishments.communityservice;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PrisonBoundsTest {
  @Test
  void explicitPrisonUsesExactlyTheSelectedBlocksWithoutLegacyHeadroom() {
    QuarryBounds bounds = QuarryBounds.between(-3, 60, -5, 8, 80, 10);
    assertTrue(bounds.containsPoint(-3, 60, -5));
    assertTrue(bounds.containsPoint(8.999, 80.999, 10.999));
    assertFalse(bounds.containsPoint(9, 70, 0));
    assertFalse(bounds.containsPoint(0, 81, 0));
    assertFalse(bounds.containsPoint(0, 70, 11));
    assertFalse(bounds.containsPoint(-3.001, 70, 0));
    assertTrue(bounds.containsPrisoner(0, 84, 0));
    assertFalse(bounds.containsPoint(0, 84, 0));
  }

  @Test
  void fullContainmentRequiresBothQuarryCornersInsidePrison() {
    QuarryBounds prison = new QuarryBounds(-10, 0, -10, 10, 100, 10);
    assertTrue(prison.contains(new QuarryBounds(-10, 0, -10, 10, 100, 10)));
    assertTrue(prison.contains(new QuarryBounds(-1, 60, -1, 1, 61, 1)));
    assertFalse(prison.contains(new QuarryBounds(-11, 60, 0, 1, 61, 1)));
    assertFalse(prison.contains(new QuarryBounds(0, 60, 0, 1, 101, 1)));
    assertFalse(prison.contains(new QuarryBounds(0, 60, 0, 1, 61, 11)));
    assertFalse(prison.contains((QuarryBounds) null));
  }

  @Test
  void invalidContinuousCoordinatesAreNeverInsidePrison() {
    QuarryBounds bounds = new QuarryBounds(-10, 0, -10, 10, 100, 10);
    assertFalse(bounds.containsPoint(Double.NaN, 64, 0));
    assertFalse(bounds.containsPoint(0, Double.NaN, 0));
    assertFalse(bounds.containsPoint(0, 64, Double.POSITIVE_INFINITY));
    assertFalse(bounds.containsPoint(Double.NEGATIVE_INFINITY, 64, 0));
  }
}
