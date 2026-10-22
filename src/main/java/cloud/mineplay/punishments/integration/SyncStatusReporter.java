package cloud.mineplay.punishments.integration;

import java.util.Objects;
import java.util.logging.Logger;

/** Worker-confined logging state; retries and scheduled waits do not produce repeated notices. */
final class SyncStatusReporter {
  private final Logger logger;
  private final boolean enabled;
  private String lastFailure;

  SyncStatusReporter(Logger logger, boolean enabled) {
    this.logger = Objects.requireNonNull(logger);
    this.enabled = enabled;
  }

  void report(SyncEngine.CycleResult result) {
    switch (result.outcome()) {
      case SKIPPED -> {
        // Waiting for a healthy poll or a failed attempt's backoff changes no outage state.
      }
      case FAILED -> {
        String category = Objects.requireNonNull(result.category());
        if (enabled && !category.equals(lastFailure))
          logger.warning(
              "event=website_sync_paused category=" + category + " local_enforcement=retained");
        lastFailure = category;
      }
      case SUCCEEDED -> {
        if (enabled && lastFailure != null)
          logger.info("event=website_sync_resumed local_enforcement=retained");
        lastFailure = null;
      }
    }
  }
}
