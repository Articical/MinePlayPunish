package cloud.mineplay.punishments.communityservice;

import static cloud.mineplay.punishments.communityservice.ServiceRepository.*;
import static org.junit.jupiter.api.Assertions.*;

import cloud.mineplay.punishments.punishment.Models.*;
import cloud.mineplay.punishments.punishment.PunishmentService;
import cloud.mineplay.punishments.storage.Database;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class ServiceRepositoryTest {
  @TempDir Path directory;
  Database database;
  PunishmentService service;
  ServiceRepository repository;

  @BeforeEach
  void setup() throws Exception {
    database = new Database(directory.resolve("test.db"));
    database.initialize();
    service = new PunishmentService(database, "test", Clock.systemUTC());
    service.refresh().join();
    repository = new ServiceRepository(database, service);
    repository.initialize().join();
  }

  @AfterEach
  void close() throws Exception {
    database.close();
  }

  private Sentence assigned() {
    UUID player = UUID.randomUUID();
    JsonObject data = new JsonObject();
    data.addProperty("reason", "Test sentence");
    data.addProperty("blocks", 10);
    data.addProperty("tool", "WOODEN_PICKAXE");
    Result result =
        service
            .apply(
                new Action(
                    UUID.randomUUID(),
                    ActionType.CREATE_COMMUNITY_SERVICE,
                    player,
                    "Tester",
                    null,
                    null,
                    "CONSOLE",
                    Source.CONSOLE,
                    Instant.now(),
                    null,
                    data))
            .join();
    assertTrue(result.applied(), result.detail());
    return service.sentence(player).orElseThrow();
  }

  private Sentence active() {
    Sentence sentence = assigned();
    repository.capture(sentence, "opaque snapshot").join();
    assertTrue(repository.activate(sentence).join());
    service.refresh().join();
    return service.sentence(sentence.targetId()).orElseThrow();
  }

  private Regeneration row(int x) {
    return new Regeneration(
        new Position(UUID.fromString("00000000-0000-0000-0000-000000000001"), x, 64, 0),
        "STONE",
        System.currentTimeMillis() + 3000);
  }

  @Test
  void captureIsDurableAndNeverOverwritesOriginal() {
    Sentence s = assigned();
    Snapshot first = repository.capture(s, "original equipment").join();
    Snapshot second = repository.capture(s, "prison equipment").join();
    assertEquals(first.id(), second.id());
    assertEquals("original equipment", second.json());
    repository.snapshot(s.id()).join().orElseThrow().verify();
    service.refresh().join();
    assertEquals("SNAPSHOTTED", service.sentence(s.targetId()).orElseThrow().state());
  }

  @Test
  void concurrentMinersCannotCreditSameRegenerationCycle() {
    Sentence first = active(), second = active();
    Regeneration same = row(1);
    CompletableFuture<Credit> a = repository.credit(first, same),
        b = repository.credit(second, same);
    assertTrue(a.join().accepted());
    assertFalse(b.join().accepted());
    service.refresh().join();
    assertEquals(1, service.sentence(first.targetId()).orElseThrow().completed());
    assertEquals(0, service.sentence(second.targetId()).orElseThrow().completed());
    assertEquals(1, repository.regenerations().join().size());
    repository.finishRegeneration(same.position()).join();
    assertTrue(repository.credit(second, same).join().accepted());
  }

  @Test
  void unfinishedInventoryCannotBeDiscardedOnStaffRelease() {
    Sentence s = active();
    repository.emergencyRelease(s, "Staff").join();
    assertThrows(CompletionException.class, () -> repository.complete(s, false).join());
    assertTrue(repository.snapshot(s.id()).join().isPresent());
    assertTrue(repository.beginRestore(s).join());
    repository.complete(s, true).join();
    service.refresh().join();
    assertTrue(service.sentence(s.targetId()).isEmpty());
    assertEquals("CONFIRMED", repository.snapshot(s.id()).join().orElseThrow().state());
    assertEquals(Status.COMPLETED, service.find(s.punishmentId()).orElseThrow().status());
  }

  @Test
  void offlineReleaseNeedsNoInventedSnapshot() {
    Sentence s = assigned();
    repository.emergencyRelease(s, "Staff").join();
    repository.complete(s, false).join();
    service.refresh().join();
    assertTrue(service.sentence(s.targetId()).isEmpty());
    assertTrue(repository.snapshot(s.id()).join().isEmpty());
  }

  @Test
  void fullWorkStopsFurtherCreditUntilVerifiedRestoration() {
    Sentence s = active();
    for (int i = 0; i < 10; i++) assertTrue(repository.credit(s, row(i)).join().accepted());
    assertFalse(repository.credit(s, row(11)).join().accepted());
    service.refresh().join();
    assertEquals("WORK_COMPLETE", service.sentence(s.targetId()).orElseThrow().state());
    assertEquals(10, service.sentence(s.targetId()).orElseThrow().completed());
    assertTrue(repository.snapshot(s.id()).join().isPresent());
  }

  @Test
  void restartRetainsSnapshotProgressAndPendingRegeneration() throws Exception {
    Sentence s = active();
    repository.credit(s, row(2)).join();
    database.close();
    database = new Database(directory.resolve("test.db"));
    database.initialize();
    service = new PunishmentService(database, "test", Clock.systemUTC());
    service.refresh().join();
    repository = new ServiceRepository(database, service);
    repository.initialize().join();
    assertEquals(1, service.sentence(s.targetId()).orElseThrow().completed());
    assertEquals(1, repository.regenerations().join().size());
    Snapshot snapshot = repository.snapshot(s.id()).join().orElseThrow();
    snapshot.verify();
    assertEquals("opaque snapshot", snapshot.json());
  }

  @Test
  void failedOutboxWriteRollsBackCreditAndRegenerationTogether() {
    Sentence s = active();
    database
        .write(
            c -> {
              try (var q = c.createStatement()) {
                q.execute(
                    "CREATE TRIGGER fail_progress BEFORE INSERT ON sync_outbox WHEN"
                        + " NEW.event_type='community_service.progress' BEGIN SELECT"
                        + " RAISE(ABORT,'test failure'); END");
              }
              return null;
            })
        .join();
    assertThrows(CompletionException.class, () -> repository.credit(s, row(5)).join());
    service.refresh().join();
    assertEquals(0, service.sentence(s.targetId()).orElseThrow().completed());
    assertTrue(repository.regenerations().join().isEmpty());
  }

  @Test
  void recoveryStateRetainsOriginalAndPreventsNewSentence() {
    Sentence s = active();
    repository.recovery(s).join();
    service.refresh().join();
    assertEquals("RECOVERY", service.sentence(s.targetId()).orElseThrow().state());
    assertEquals("opaque snapshot", repository.snapshot(s.id()).join().orElseThrow().json());
    assertFalse(repository.credit(s, row(8)).join().accepted());
  }

  @Test
  void lateFailureOrRepeatedCompletionCannotUndoConfirmedRelease() {
    Sentence s = active();
    repository.emergencyRelease(s, "Staff").join();
    assertTrue(repository.beginRestore(s).join());
    repository.complete(s, true).join();
    service.refresh().join();
    long revision = service.find(s.punishmentId()).orElseThrow().revision();
    repository.recovery(s).join();
    repository.emergencyRelease(s, "Late callback").join();
    repository.complete(s, true).join();
    repository.complete(s, false).join();
    service.refresh().join();
    assertTrue(service.healthy());
    assertTrue(service.sentence(s.targetId()).isEmpty());
    assertEquals("CONFIRMED", repository.snapshot(s.id()).join().orElseThrow().state());
    assertEquals(Status.COMPLETED, service.find(s.punishmentId()).orElseThrow().status());
    assertEquals(revision, service.find(s.punishmentId()).orElseThrow().revision());
  }

  @Test
  void repeatedRecoveryKeepsSnapshotAndInFlightRestorationIsNotReset() {
    Sentence s = active();
    repository.recovery(s).join();
    service.refresh().join();
    long revision = service.find(s.punishmentId()).orElseThrow().revision();
    repository.recovery(s).join();
    service.refresh().join();
    assertEquals(revision, service.find(s.punishmentId()).orElseThrow().revision());
    assertEquals("RECOVERY", repository.snapshot(s.id()).join().orElseThrow().state());
    repository.emergencyRelease(s, "Staff").join();
    assertTrue(repository.beginRestore(s).join());
    repository.emergencyRelease(s, "Duplicate recovery").join();
    service.refresh().join();
    assertEquals("RESTORING", service.sentence(s.targetId()).orElseThrow().state());
    assertThrows(CompletionException.class, () -> repository.complete(s, false).join());
    assertEquals("opaque snapshot", repository.snapshot(s.id()).join().orElseThrow().json());
  }
}
