package cloud.mineplay.punishments.integration;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Shared by every request lane and replacement sync client. Only the sync worker may wait here. */
final class ApiRequestGate {
  private static final long SPACING_NANOS = Duration.ofMillis(350).toNanos();
  private static final ApiRequestGate SHARED =
      new ApiRequestGate(Clock.systemUTC(), System::nanoTime, Thread::sleep, SPACING_NANOS);
  private final Clock clock;
  private final LongSupplier nanoTime;
  private final Sleeper sleeper;
  private final long spacingNanos;
  private boolean sent;
  private long previousRequest;
  private Instant retryAt = Instant.MIN;

  @FunctionalInterface
  interface Sleeper {
    void sleep(long milliseconds) throws InterruptedException;
  }

  ApiRequestGate(Clock clock, LongSupplier nanoTime, Sleeper sleeper, long spacingNanos) {
    this.clock = clock;
    this.nanoTime = nanoTime;
    this.sleeper = sleeper;
    this.spacingNanos = spacingNanos;
  }

  static ApiRequestGate shared() {
    return SHARED;
  }

  /** Direct engine callers can supply a deterministic clock without sleeping in domain tests. */
  static ApiRequestGate unpaced(Clock clock) {
    return new ApiRequestGate(clock, System::nanoTime, Thread::sleep, 0);
  }

  synchronized Instant retryAt() {
    return retryAt;
  }

  synchronized void deferUntil(Instant deadline) {
    if (deadline != null && deadline.isAfter(retryAt)) retryAt = deadline;
  }

  synchronized void acquire(BooleanSupplier closed) {
    while (true) {
      if (closed.getAsBoolean() || Thread.currentThread().isInterrupted())
        throw new ApiFailure("STOPPED");
      if (clock.instant().isBefore(retryAt)) throw new ApiFailure("HTTP_TRANSIENT", 0, retryAt);
      long now = nanoTime.getAsLong();
      long wait = sent ? spacingNanos - (now - previousRequest) : 0;
      if (wait <= 0) {
        previousRequest = now;
        sent = true;
        return;
      }
      try {
        // A short interruptible wait also permits prompt plugin shutdown.
        sleeper.sleep(Math.max(1, Math.min(100, (wait + 999_999) / 1_000_000)));
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new ApiFailure("STOPPED");
      }
    }
  }
}
