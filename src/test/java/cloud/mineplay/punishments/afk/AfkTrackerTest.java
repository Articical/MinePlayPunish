package cloud.mineplay.punishments.afk;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class AfkTrackerTest {
  private static final AfkSettings DEFAULTS = new AfkSettings(true, 900, 120, 5);
  private static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID OTHER_WORLD = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private final AtomicLong clock = new AtomicLong();
  private final AtomicInteger codes = new AtomicInteger();
  private final AfkTracker tracker =
      new AfkTracker(clock::get, () -> String.format("%08X", codes.incrementAndGet()));

  private void seconds(long seconds) {
    clock.addAndGet(TimeUnit.SECONDS.toNanos(seconds));
  }

  private AfkTracker.Decision decision() {
    return tracker.evaluate(DEFAULTS);
  }

  @Test
  void idlePlayerGetsOneWarningThenKickAtFifteenMinutes() {
    seconds(779);
    assertEquals(AfkTracker.Outcome.NONE, decision().outcome());
    seconds(1);
    var warning = decision();
    assertEquals(AfkTracker.Outcome.WARN, warning.outcome());
    assertEquals("00000001", warning.code());
    assertEquals(120, warning.remainingSeconds());
    seconds(119);
    assertEquals(AfkTracker.Outcome.NONE, decision().outcome());
    seconds(1);
    assertEquals(AfkTracker.Outcome.KICK, decision().outcome());
    assertEquals(1, codes.get(), "Periodic checks must not replace the player's pending challenge");
  }

  @Test
  void delayedFirstCheckAlwaysProvidesTheWholeWarningGrace() {
    seconds(3600);
    var warning = decision();
    assertEquals(AfkTracker.Outcome.WARN, warning.outcome());
    assertEquals(120, warning.remainingSeconds());
    seconds(119);
    assertEquals(AfkTracker.Outcome.NONE, decision().outcome());
    seconds(1);
    assertEquals(AfkTracker.Outcome.KICK, decision().outcome());
  }

  @Test
  void clockWraparoundDoesNotLoseTheIdleDeadline() {
    clock.set(Long.MAX_VALUE - TimeUnit.SECONDS.toNanos(60));
    var wrapped = new AfkTracker(clock::get, () -> "ABCDEF01");
    seconds(780);
    assertEquals(AfkTracker.Outcome.WARN, wrapped.evaluate(DEFAULTS).outcome());
    seconds(120);
    assertEquals(AfkTracker.Outcome.KICK, wrapped.evaluate(DEFAULTS).outcome());
  }

  @Test
  void passiveWaterOrVehicleMovementDoesNotCountEvenOverNewTerrain() {
    tracker.sample(WORLD, 0, 0, false);
    for (int i = 1; i <= 156; i++) {
      seconds(5);
      tracker.sample(WORLD, i * 8, i * 4, false);
    }
    assertEquals(AfkTracker.Outcome.WARN, decision().outcome());
    seconds(120);
    tracker.sample(WORLD, 4096, 4096, false);
    assertEquals(AfkTracker.Outcome.KICK, decision().outcome());
  }

  @Test
  void passiveSamplesDoNotPoisonLaterManualMovementHistory() {
    tracker.sample(WORLD, 0, 0, false);
    tracker.sample(WORLD, 8, 0, false);
    tracker.sample(WORLD, 0, 0, false);
    seconds(779);
    tracker.sample(WORLD, 8, 0, true);
    seconds(1);
    assertEquals(AfkTracker.Outcome.NONE, decision().outcome());
    seconds(779);
    assertEquals(AfkTracker.Outcome.WARN, decision().outcome());
  }

  @Test
  void stationaryJumpingSpinningAndControlsAgainstWallNeverCountAsTravel() {
    tracker.sample(WORLD, 1, 1, true);
    for (int i = 0; i < 156; i++) {
      seconds(5);
      // No Y/yaw/pitch are accepted by the tracker; tiny movement inside one cell is not travel.
      tracker.sample(WORLD, i % 2 == 0 ? 1.0 : 1.25, 1, true);
    }
    assertEquals(AfkTracker.Outcome.WARN, decision().outcome());
    seconds(120);
    tracker.sample(WORLD, 1, 1, true);
    assertEquals(AfkTracker.Outcome.KICK, decision().outcome());
  }

  @Test
  void constantForwardExplorationCountsWithoutRequiringInputChanges() {
    tracker.sample(WORLD, 0, 0, true);
    for (int i = 1; i <= 1000; i++) {
      seconds(5);
      tracker.sample(WORLD, i * 4, 0, true);
      assertEquals(AfkTracker.Outcome.NONE, decision().outcome());
    }
  }

  @Test
  void repeatingWalkingCircuitStopsCreditingAfterItsFirstTraversal() {
    tracker.sample(WORLD, 0, 0, true);
    for (int i = 1; i <= 4; i++) {
      seconds(5);
      tracker.sample(WORLD, i * 4, 0, true);
    }
    for (int i = 0; i < 156; i++) {
      seconds(5);
      tracker.sample(WORLD, (i % 5) * 4, 0, true);
    }
    assertEquals(AfkTracker.Outcome.WARN, decision().outcome());
    seconds(120);
    tracker.sample(WORLD, 8, 0, true);
    assertEquals(AfkTracker.Outcome.KICK, decision().outcome());
  }

  @Test
  void negativeCoordinatesUseFloorCellsRatherThanTruncation() {
    tracker.sample(WORLD, 0.5, 0, true);
    seconds(779);
    tracker.sample(WORLD, -0.5, 0, true);
    seconds(1);
    assertEquals(AfkTracker.Outcome.NONE, decision().outcome());
  }

  @Test
  void initialSampleTeleportAndWorldChangeDoNotCountAsActivity() {
    seconds(779);
    tracker.sample(WORLD, 0, 0, true);
    tracker.invalidatePosition();
    tracker.sample(WORLD, 1000, 1000, true);
    tracker.sample(OTHER_WORLD, 10, 10, true);
    seconds(1);
    assertEquals(AfkTracker.Outcome.WARN, decision().outcome());
  }

  @Test
  void invalidPositionCannotManufactureTravel() {
    tracker.sample(WORLD, 0, 0, true);
    seconds(779);
    tracker.sample(WORLD, Double.NaN, 0, true);
    tracker.sample(WORLD, 1000, 0, true);
    tracker.sample(null, 0, 0, true);
    tracker.sample(WORLD, 2000, 0, true);
    tracker.sample(WORLD, Double.POSITIVE_INFINITY, 0, true);
    tracker.sample(WORLD, 3000, 0, true);
    seconds(1);
    assertEquals(AfkTracker.Outcome.WARN, decision().outcome());
  }

  @Test
  void repeatedBreakPlaceAtOneCoordinateCannotKeepAPlayerOnline() {
    tracker.action(WORLD, 1, 64, 1);
    seconds(780);
    tracker.action(WORLD, 1, 64, 1);
    assertEquals(AfkTracker.Outcome.WARN, decision().outcome());
    seconds(120);
    tracker.action(WORLD, 1, 64, 1);
    assertEquals(AfkTracker.Outcome.KICK, decision().outcome());
  }

  @Test
  void distinctSuccessfulMiningOrBuildingKeepsThePlayerActive() {
    for (int i = 0; i < 1000; i++) {
      seconds(10);
      tracker.action(WORLD, 0, 64, i);
      assertEquals(AfkTracker.Outcome.NONE, decision().outcome());
    }
  }

  @Test
  void blockCoordinatesRemainDistinctAcrossWorldsAndHeights() {
    tracker.action(WORLD, 0, 64, 0);
    seconds(779);
    tracker.action(OTHER_WORLD, 0, 64, 0);
    seconds(779);
    tracker.action(OTHER_WORLD, 0, 65, 0);
    seconds(779);
    assertEquals(AfkTracker.Outcome.NONE, decision().outcome());
  }

  @Test
  void repeatedChatFingerprintsDoNotCountButDistinctDeliveredChatDoes() {
    tracker.chat("first-sha256-fingerprint");
    seconds(780);
    tracker.chat("first-sha256-fingerprint");
    assertEquals(AfkTracker.Outcome.WARN, decision().outcome());
    tracker.chat("second-sha256-fingerprint");
    assertEquals(AfkTracker.Outcome.NONE, decision().outcome());
    seconds(780);
    tracker.chat("first-sha256-fingerprint");
    tracker.chat("second-sha256-fingerprint");
    assertEquals(AfkTracker.Outcome.WARN, decision().outcome());
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" ", "\t"})
  void absentChatFingerprintNeverCreditsActivity(String fingerprint) {
    seconds(780);
    tracker.chat(fingerprint);
    assertEquals(AfkTracker.Outcome.WARN, decision().outcome());
  }

  @Test
  void oversizedChatFingerprintNeverCreditsActivity() {
    seconds(780);
    tracker.chat("x".repeat(129));
    assertEquals(AfkTracker.Outcome.WARN, decision().outcome());
  }

  @Test
  void validConfirmationResetsIdleAndConsumesTheChallenge() {
    seconds(780);
    String code = decision().code();
    seconds(119);
    assertTrue(tracker.confirm(code.toLowerCase(java.util.Locale.ROOT), DEFAULTS));
    assertFalse(tracker.confirm(code, DEFAULTS));
    seconds(779);
    assertEquals(AfkTracker.Outcome.NONE, decision().outcome());
    seconds(1);
    var next = decision();
    assertEquals(AfkTracker.Outcome.WARN, next.outcome());
    assertNotEquals(code, next.code());
    assertFalse(tracker.confirm(code, DEFAULTS));
  }

  @Test
  void confirmationAcceptsUppercaseOrLowercaseChallengeLetters() {
    var letterCodeTracker = new AfkTracker(clock::get, () -> "ABCD1234");
    seconds(780);
    assertEquals("ABCD1234", letterCodeTracker.evaluate(DEFAULTS).code());
    assertTrue(letterCodeTracker.confirm("abcd1234", DEFAULTS));
  }

  @Test
  void confirmationDoesNotEraseWalkingOrBlockLoopHistory() {
    tracker.sample(WORLD, 0, 0, true);
    tracker.sample(WORLD, 4, 0, true);
    tracker.action(WORLD, 0, 64, 0);
    seconds(780);
    assertTrue(tracker.confirm(decision().code(), DEFAULTS));
    seconds(780);
    tracker.sample(WORLD, 0, 0, true);
    tracker.sample(WORLD, 4, 0, true);
    tracker.action(WORLD, 0, 64, 0);
    assertEquals(AfkTracker.Outcome.WARN, decision().outcome());
  }

  @Test
  void ordinaryActivityConsumesPendingCodeAndKeepsOtherNoveltyHistory() {
    tracker.action(WORLD, 0, 64, 0);
    seconds(780);
    String code = decision().code();
    tracker.chat("new-chat");
    assertFalse(tracker.confirm(code, DEFAULTS));
    seconds(780);
    tracker.action(WORLD, 0, 64, 0);
    assertEquals(AfkTracker.Outcome.WARN, decision().outcome());
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"WRONG123", "00000001 extra"})
  void invalidConfirmationDoesNotExtendGrace(String code) {
    seconds(780);
    decision();
    seconds(119);
    assertFalse(tracker.confirm(code, DEFAULTS));
    seconds(1);
    assertEquals(AfkTracker.Outcome.KICK, decision().outcome());
  }

  @Test
  void expiredCodeCannotResetAPlayerBeforeDelayedKickCheck() {
    seconds(780);
    String code = decision().code();
    seconds(120);
    assertFalse(tracker.confirm(code, DEFAULTS));
    assertEquals(AfkTracker.Outcome.KICK, decision().outcome());
  }

  @Test
  void challengeCannotConfirmAnotherConnectionOrPlayer() {
    seconds(780);
    String code = decision().code();
    var nextConnection = new AfkTracker(clock::get, () -> "NEWCODE1");
    assertFalse(nextConnection.confirm(code, DEFAULTS));
    seconds(780);
    assertEquals(AfkTracker.Outcome.WARN, nextConnection.evaluate(DEFAULTS).outcome());
    assertFalse(nextConnection.confirm(code, DEFAULTS));
  }

  @Test
  void shorterReloadDoesNotRetractThePromisedWarningGrace() {
    seconds(780);
    String code = decision().code();
    AfkSettings shortened = new AfkSettings(true, 60, 5, 1);
    seconds(119);
    assertEquals(AfkTracker.Outcome.NONE, tracker.evaluate(shortened).outcome());
    assertTrue(tracker.confirm(code, shortened));
  }

  @Test
  void longerReloadKeepsTheExistingConfirmationUsable() {
    seconds(780);
    String code = decision().code();
    AfkSettings extended = new AfkSettings(true, 3600, 300, 5);
    seconds(300);
    assertEquals(AfkTracker.Outcome.NONE, tracker.evaluate(extended).outcome());
    assertTrue(tracker.confirm(code, extended));
  }

  @Test
  void increasedWarningPeriodCanExtendAnExistingGrace() {
    seconds(780);
    String code = decision().code();
    AfkSettings extendedWarning = new AfkSettings(true, 900, 300, 5);
    seconds(299);
    assertEquals(AfkTracker.Outcome.NONE, tracker.evaluate(extendedWarning).outcome());
    assertTrue(tracker.confirm(code, extendedWarning));
  }

  @Test
  void disabledDetectorDoesNotWarnOrKickAndConsumesItsChallenge() {
    seconds(780);
    String code = decision().code();
    AfkSettings disabled = new AfkSettings(false, 900, 120, 5);
    seconds(3600);
    assertEquals(AfkTracker.Outcome.NONE, tracker.evaluate(disabled).outcome());
    assertFalse(tracker.confirm(code, disabled));
    var warning = decision();
    assertEquals(AfkTracker.Outcome.WARN, warning.outcome());
    assertEquals(120, warning.remainingSeconds());
  }

  @Test
  void cancelledKickIssuesFreshWarningWithoutResettingIdleOrMachineHistory() {
    tracker.action(WORLD, 0, 64, 0);
    seconds(780);
    String old = decision().code();
    seconds(120);
    assertEquals(AfkTracker.Outcome.KICK, decision().outcome());
    tracker.cancelledKick();
    assertFalse(tracker.confirm(old, DEFAULTS));
    var renewed = decision();
    assertEquals(AfkTracker.Outcome.WARN, renewed.outcome());
    assertEquals(120, renewed.remainingSeconds());
    assertNotEquals(old, renewed.code());
    seconds(120);
    tracker.action(WORLD, 0, 64, 0);
    assertEquals(AfkTracker.Outcome.KICK, decision().outcome());
  }

  @Test
  void memoryRemainsBoundedDuringLongExplorationBuildingAndChat() throws Exception {
    tracker.sample(WORLD, 0, 0, true);
    for (int i = 0; i < 10_000; i++) {
      tracker.sample(WORLD, i * 4, 0, true);
      tracker.action(WORLD, i, 64, 0);
      tracker.chat("fingerprint-" + i);
    }
    assertEquals(256, historySize("positions"));
    assertEquals(256, historySize("actions"));
    assertEquals(32, historySize("chats"));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"x", "code with spaces", "<click:run_command:/op>"})
  void brokenChallengeGeneratorNeverProducesAnUnsafeWarning(String code) {
    var broken = new AfkTracker(clock::get, () -> code);
    seconds(780);
    assertThrows(IllegalStateException.class, () -> broken.evaluate(DEFAULTS));
  }

  private int historySize(String name) throws Exception {
    var field = AfkTracker.class.getDeclaredField(name);
    field.setAccessible(true);
    Object history = field.get(tracker);
    var entries = history.getClass().getDeclaredField("entries");
    entries.setAccessible(true);
    return ((Set<?>) entries.get(history)).size();
  }
}
