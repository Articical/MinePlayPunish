package cloud.mineplay.punishments.integration;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ApiRequestGateTest {
  @Test
  void allLanesSharingGateStayBelowSharedMinuteLimit() {
    AtomicLong nanos = new AtomicLong();
    Clock clock = Clock.fixed(Instant.parse("2026-11-09T12:00:00Z"), ZoneOffset.UTC);
    ApiRequestGate gate =
        new ApiRequestGate(
            clock,
            nanos::get,
            milliseconds -> nanos.addAndGet(milliseconds * 1_000_000),
            Duration.ofMillis(350).toNanos());
    List<Long> requests = new ArrayList<>();
    for (int i = 0; i < 400; i++) {
      // These alternate between every moderation endpoint and replacement client.
      gate.acquire(() -> false);
      requests.add(nanos.get());
    }
    for (long start : requests)
      assertTrue(
          requests.stream()
                  .filter(time -> time >= start && time < start + Duration.ofMinutes(1).toNanos())
                  .count()
              <= 172);
    for (int i = 1; i < requests.size(); i++)
      assertTrue(requests.get(i) - requests.get(i - 1) >= Duration.ofMillis(350).toNanos());
  }

  @Test
  void retryAfterCannotBeBypassedByANewRequestLaneOrAnEarlierDeadline() {
    SyncEngineTest.MutableClock clock = new SyncEngineTest.MutableClock();
    ApiRequestGate gate = ApiRequestGate.unpaced(clock);
    Instant deadline = clock.instant().plusSeconds(90);
    gate.deferUntil(deadline);
    gate.deferUntil(clock.instant().plusSeconds(10));
    ApiFailure blocked = assertThrows(ApiFailure.class, () -> gate.acquire(() -> false));
    assertEquals(deadline, blocked.retryAt);
    clock.now = deadline;
    assertDoesNotThrow(() -> gate.acquire(() -> false));
  }

  @Test
  void pacingWaitStopsPromptlyWhenInterrupted() {
    ApiRequestGate gate =
        new ApiRequestGate(
            Clock.systemUTC(),
            () -> 0,
            milliseconds -> {
              throw new InterruptedException();
            },
            350_000_000);
    gate.acquire(() -> false);
    try {
      assertEquals(
          "STOPPED", assertThrows(ApiFailure.class, () -> gate.acquire(() -> false)).category);
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void stoppedClientConsumesNoRequestSlot() {
    ApiRequestGate gate = ApiRequestGate.unpaced(Clock.systemUTC());
    assertEquals(
        "STOPPED", assertThrows(ApiFailure.class, () -> gate.acquire(() -> true)).category);
  }
}
