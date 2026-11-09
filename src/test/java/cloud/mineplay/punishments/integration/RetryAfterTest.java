package cloud.mineplay.punishments.integration;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class RetryAfterTest {
  private static final Instant NOW = Instant.parse("2026-11-09T12:00:00Z");

  @Test
  void acceptsDelaySecondsAndHttpDateWithoutCappingServerDelay() {
    assertEquals(NOW.plusSeconds(3600), RetryAfter.deadline("3600", NOW).orElseThrow());
    assertEquals(
        NOW.plusSeconds(90),
        RetryAfter.deadline("Mon, 09 Nov 2026 12:01:30 GMT", NOW).orElseThrow());
    assertEquals(NOW, RetryAfter.deadline("Mon, 09 Nov 2026 11:59:00 GMT", NOW).orElseThrow());
    assertEquals(NOW, RetryAfter.deadline("0", NOW).orElseThrow());
    assertEquals(Instant.MAX, RetryAfter.deadline("99999999999999999999999999", NOW).orElseThrow());
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(
      strings = {
        "-1",
        "+30",
        "1.5",
        "nan",
        "secret data",
        "1,000",
        "Mon, 99 Nov 2026 12:00:00 GMT"
      })
  void invalidHeadersAreNotReflectedOrUsed(String value) {
    assertTrue(RetryAfter.deadline(value, NOW).isEmpty());
  }
}
