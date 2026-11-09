package cloud.mineplay.punishments.telemetry;

import static cloud.mineplay.punishments.telemetry.TelemetryObservation.*;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TelemetryObservationTest {
  @ParameterizedTest
  @ValueSource(strings = {"0", "-0", "1250.500000", "-0.000001", "999999999999999999.999999"})
  void validDecimalIsAlwaysStoredAsText(String amount) {
    assertEquals(
        amount, new Economy(Status.AVAILABLE, amount, "Vault provider", "coins").balance());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "+1",
        "01",
        "-01",
        "1e3",
        "1,000",
        "NaN",
        "Infinity",
        "1000000000000000000",
        "0.1234567",
        "1.",
        ".5",
        " 1"
      })
  void invalidDecimalIsNeverSilentlyRounded(String amount) {
    assertThrows(
        IllegalArgumentException.class,
        () -> new Economy(Status.AVAILABLE, amount, "Vault provider", "coins"));
  }

  @Test
  void vaultConversionRejectsUnsupportedValuesAndPreservesZeroAndNegative() {
    for (double value :
        new double[] {
          Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 1e18, 0.1234567
        }) assertEquals(Status.UNAVAILABLE, Economy.available(value, "Provider", "coins").status());
    assertEquals("0", Economy.available(0, "Provider", "coins").balance());
    assertEquals("-12.5", Economy.available(-12.5, "Provider", "coins").balance());
  }

  @Test
  void nonAvailableEconomyCannotCarryBalanceAndAvailableNeedsLabels() {
    for (Status status : List.of(Status.UNAVAILABLE, Status.NO_ACCOUNT, Status.NOT_INSTALLED))
      assertThrows(IllegalArgumentException.class, () -> new Economy(status, "0", null, null));
    assertThrows(
        IllegalArgumentException.class, () -> new Economy(Status.AVAILABLE, "0", " ", "coins"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Economy(Status.AVAILABLE, "0", "Provider", "x".repeat(33)));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "localhost",
        "example.com",
        "127.1",
        "1.2.3",
        "1.2.3.4:25565",
        "[::1]",
        "fe80::1%eth0",
        "01.2.3.4",
        "256.2.3.4",
        " 1.2.3.4",
        "::ffff:01.2.3.4"
      })
  void malformedAndNonLiteralAddressesAreRejectedWithoutResolution(String input) {
    assertThrows(
        IllegalArgumentException.class, () -> TelemetryObservation.normalizeAddress(input));
  }

  @Test
  void equivalentAddressesNormalizeToOneLiteral() {
    assertEquals("192.0.2.1", TelemetryObservation.normalizeAddress("::ffff:192.0.2.1"));
    assertEquals(
        TelemetryObservation.normalizeAddress("2001:db8::1"),
        TelemetryObservation.normalizeAddress("2001:0DB8:0:0:0:0:0:1"));
  }

  @Test
  void observationRejectsInvalidTimelineAndNormalizedDuplicateAddresses() {
    Instant now = Instant.parse("2026-11-09T12:00:00Z"), join = now.minusSeconds(60);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TelemetryObservation(
                UUID.randomUUID(),
                "Valid",
                now,
                join,
                join,
                now,
                true,
                null,
                null,
                Economy.unavailable(),
                List.of(),
                List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TelemetryObservation(
                UUID.randomUUID(),
                "Valid",
                now,
                join,
                join,
                null,
                true,
                null,
                null,
                Economy.unavailable(),
                List.of(),
                List.of(new Address("192.0.2.1", now), new Address("::ffff:192.0.2.1", now))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TelemetryObservation(
                UUID.randomUUID(),
                "Valid",
                now,
                null,
                join,
                null,
                true,
                null,
                null,
                Economy.unavailable(),
                List.of(),
                List.of()));
  }

  @Test
  void sessionTimesNormalizeToDatabaseMicroseconds() {
    var session =
        new Session(UUID.randomUUID(), Instant.parse("2026-11-09T12:00:00.123456789Z"), null);
    assertEquals(Instant.parse("2026-11-09T12:00:00.123456Z"), session.joinedAt());
  }
}
