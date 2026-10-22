package cloud.mineplay.punishments.integration;

import static org.mockito.Mockito.*;

import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

class SyncStatusReporterTest {
  private final Logger logger = mock(Logger.class);
  private final SyncStatusReporter reporter = new SyncStatusReporter(logger, true);

  private void success() {
    reporter.report(new SyncEngine.CycleResult(SyncEngine.CycleOutcome.SUCCEEDED, null));
  }

  private void skipped() {
    reporter.report(new SyncEngine.CycleResult(SyncEngine.CycleOutcome.SKIPPED, null));
  }

  private void failed(String category) {
    reporter.report(new SyncEngine.CycleResult(SyncEngine.CycleOutcome.FAILED, category));
  }

  @Test
  void successfulPollingAndWaitingNeverProducePauseOrRecoveryMessages() {
    for (int cycle = 0; cycle < 20; cycle++) {
      success();
      for (int tick = 0; tick < 5; tick++) skipped();
    }
    verifyNoInteractions(logger);
  }

  @Test
  void repeatedFailureAndBackoffTicksEmitOnlyTheFirstWarning() {
    for (int attempt = 0; attempt < 20; attempt++) {
      failed("AUTHENTICATION");
      for (int tick = 0; tick < 300; tick++) skipped();
    }
    verify(logger)
        .warning("event=website_sync_paused category=AUTHENTICATION local_enforcement=retained");
    verifyNoMoreInteractions(logger);
  }

  @Test
  void changedFailureCategoryRemainsVisibleWithoutRepeatedRetries() {
    failed("CREDENTIAL_MISSING");
    skipped();
    failed("DATABASE_FAILURE");
    failed("DATABASE_FAILURE");
    verify(logger)
        .warning(
            "event=website_sync_paused category=CREDENTIAL_MISSING local_enforcement=retained");
    verify(logger)
        .warning("event=website_sync_paused category=DATABASE_FAILURE local_enforcement=retained");
    verifyNoMoreInteractions(logger);
  }

  @Test
  void recoveryLogsOnceAndANewOutageCanWarnAgain() {
    failed("TIMEOUT");
    skipped();
    success();
    skipped();
    success();
    failed("TIMEOUT");
    verify(logger, times(2))
        .warning("event=website_sync_paused category=TIMEOUT local_enforcement=retained");
    verify(logger).info("event=website_sync_resumed local_enforcement=retained");
    verifyNoMoreInteractions(logger);
  }

  @Test
  void existingApiLoggingPreferenceAlsoSuppressesRecoveryNotices() {
    SyncStatusReporter quiet = new SyncStatusReporter(logger, false);
    quiet.report(new SyncEngine.CycleResult(SyncEngine.CycleOutcome.FAILED, "AUTHENTICATION"));
    quiet.report(new SyncEngine.CycleResult(SyncEngine.CycleOutcome.SKIPPED, null));
    quiet.report(new SyncEngine.CycleResult(SyncEngine.CycleOutcome.SUCCEEDED, null));
    verifyNoInteractions(logger);
  }
}
