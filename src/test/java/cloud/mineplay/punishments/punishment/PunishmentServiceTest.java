package cloud.mineplay.punishments.punishment;

import static org.junit.jupiter.api.Assertions.*;

import cloud.mineplay.punishments.punishment.Models.Action;
import cloud.mineplay.punishments.punishment.Models.ActionType;
import cloud.mineplay.punishments.punishment.Models.Punishment;
import cloud.mineplay.punishments.punishment.Models.Result;
import cloud.mineplay.punishments.punishment.Models.Source;
import cloud.mineplay.punishments.punishment.Models.Status;
import cloud.mineplay.punishments.punishment.Models.Type;
import cloud.mineplay.punishments.storage.Database;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PunishmentServiceTest {
  @TempDir Path directory;
  private Database database;
  private PunishmentService service;
  private MutableClock clock;
  private final UUID player = UUID.fromString("ac954957-c347-478c-8d66-2cab5d548329");

  @BeforeEach
  void initialize() throws Exception {
    clock = new MutableClock(Instant.parse("2026-10-18T12:00:00Z"));
    database = new Database(directory.resolve("punishments.db"));
    database.initialize();
    service = new PunishmentService(database, "test-smp", clock);
    service.refresh().join();
  }

  @AfterEach
  void close() throws Exception {
    database.close();
  }

  @Test
  void permanentBanPersistsWithAtomicOutboxAndAppealRequest() {
    Punishment punishment = applied(create(ActionType.CREATE_BAN, reason())).punishment();
    assertEquals(Status.ACTIVE, punishment.status());
    assertNull(punishment.expiresAt());
    assertEquals(punishment, service.active(player, Type.BAN).orElseThrow());
    assertEquals(1, count("punishments"));
    assertEquals(1, count("punishment_events"));
    assertEquals(1, count("sync_outbox"));
    assertEquals(1, count("appeal_requests"));
    assertEquals(1, count("local_actions"));
    assertTrue(service.healthy());
  }

  @Test
  void cacheExpiresAtExactUtcBoundaryBeforeSweepAndSweepRunsOnce() {
    JsonObject payload = reason();
    payload.addProperty("expiresAt", clock.instant().plusSeconds(60).toString());
    Punishment ban = applied(create(ActionType.CREATE_BAN, payload)).punishment();
    clock.advance(60);
    assertTrue(service.active(player, Type.BAN).isEmpty());
    assertEquals(Status.ACTIVE, service.find(ban.id()).orElseThrow().status());
    service.expire().join();
    service.expire().join();
    assertEquals(Status.EXPIRED, service.find(ban.id()).orElseThrow().status());
    assertEquals(2, count("punishment_events"));
  }

  @Test
  void permanentAndTemporaryMutesUseIndependentPlayerAndTypeIndexes() {
    applied(create(ActionType.CREATE_BAN, reason()));
    Punishment mute = applied(create(ActionType.CREATE_MUTE, reason())).punishment();
    assertNull(mute.expiresAt());
    assertTrue(service.active(player, Type.MUTE).isPresent());
    applied(modify(ActionType.REVOKE_PUNISHMENT, mute, reason()));
    JsonObject payload = reason();
    payload.addProperty("expiresAt", clock.instant().plusSeconds(1).toString());
    applied(create(ActionType.CREATE_MUTE, payload));
    clock.advance(1);
    assertTrue(service.active(player, Type.MUTE).isEmpty());
    assertTrue(service.active(player, Type.BAN).isPresent());
  }

  @Test
  void duplicateCreationAndChangedReplayAreDifferentFailures() {
    Action original = create(ActionType.CREATE_BAN, reason());
    Result first = applied(original);
    assertEquals(first, service.apply(original).join());
    assertEquals(1, count("punishments"));
    assertEquals(
        "ACTIVE_PUNISHMENT_EXISTS",
        service.apply(create(ActionType.CREATE_BAN, reason())).join().code());
    JsonObject changed = reason();
    changed.addProperty("reason", "Different reason");
    Action replay =
        new Action(
            original.id(),
            original.type(),
            player,
            "Steve",
            null,
            null,
            "Console",
            Source.CONSOLE,
            clock.instant(),
            null,
            changed);
    assertEquals("REPLAY_MISMATCH", service.apply(replay).join().code());
    assertEquals(1, count("punishment_events"));
  }

  @Test
  void equivalentJsonFieldOrderRemainsIdempotent() {
    JsonObject first = new JsonObject();
    first.addProperty("reason", "Test");
    first.addProperty("expiresAt", clock.instant().plusSeconds(60).toString());
    Action action = create(ActionType.CREATE_BAN, first);
    Result original = applied(action);
    JsonObject reordered = new JsonObject();
    reordered.addProperty("expiresAt", clock.instant().plusSeconds(60).toString());
    reordered.addProperty("reason", "Test");
    Action duplicate =
        new Action(
            action.id(),
            action.type(),
            action.targetId(),
            action.targetName(),
            action.punishmentId(),
            action.expectedRevision(),
            action.issuer(),
            action.source(),
            action.issuedAt(),
            action.validUntil(),
            reordered);
    assertEquals(original, service.apply(duplicate).join());
    assertEquals(1, count("punishments"));
  }

  @Test
  void revocationPreservesHistoryAndRejectsStaleWebsiteRevision() {
    Punishment ban = applied(create(ActionType.CREATE_BAN, reason())).punishment();
    JsonObject reduce = new JsonObject();
    reduce.addProperty("expiresAt", clock.instant().plusSeconds(120).toString());
    Punishment modified = applied(modify(ActionType.MODIFY_EXPIRATION, ban, reduce)).punishment();
    assertEquals(2, modified.revision());
    assertEquals(
        "REVISION_CONFLICT",
        service.apply(modify(ActionType.REVOKE_PUNISHMENT, ban, reason())).join().code());
    Punishment revoked =
        applied(modify(ActionType.REVOKE_PUNISHMENT, modified, reason())).punishment();
    assertEquals(Status.REVOKED, revoked.status());
    assertTrue(service.active(player, Type.BAN).isEmpty());
    assertEquals(1, service.history(player).join().size());
    assertEquals("Console", value("SELECT revoked_by FROM punishments"));
  }

  @Test
  void reducingExpirationIntoThePastEndsPunishmentImmediately() {
    Punishment mute = applied(create(ActionType.CREATE_MUTE, reason())).punishment();
    JsonObject reduced = new JsonObject();
    reduced.addProperty("expiresAt", clock.instant().minusSeconds(1).toString());
    Result result = applied(modify(ActionType.MODIFY_EXPIRATION, mute, reduced));
    assertEquals(Status.EXPIRED, result.punishment().status());
    assertTrue(service.active(player, Type.MUTE).isEmpty());
  }

  @Test
  void offlineWarningsSurviveRestartAndTrackDeliverySeparatelyFromAcknowledgement()
      throws Exception {
    Punishment warning = applied(create(ActionType.CREATE_WARNING, reason())).punishment();
    restart();
    assertEquals(List.of(warning), service.pendingWarnings(player).join());
    assertThrows(
        CompletionException.class, () -> service.acknowledgeWarning(player, warning.id()).join());
    assertTrue(service.healthy());
    assertNull(value("SELECT acknowledged_at FROM pending_notifications"));
    service.markWarningDelivered(warning.id()).join();
    service.markWarningDelivered(warning.id()).join();
    assertTrue(service.pendingWarnings(player).join().isEmpty());
    assertNull(value("SELECT acknowledged_at FROM pending_notifications"));
    service.acknowledgeWarning(player, warning.id()).join();
    service.acknowledgeWarning(player, warning.id()).join();
    assertNotNull(value("SELECT acknowledged_at FROM pending_notifications"));
    assertEquals(3, count("punishment_events"));
  }

  @Test
  void anotherPlayerCannotAcknowledgeWarning() {
    Punishment warning = applied(create(ActionType.CREATE_WARNING, reason())).punishment();
    service.markWarningDelivered(warning.id()).join();
    assertThrows(
        CompletionException.class,
        () -> service.acknowledgeWarning(UUID.randomUUID(), warning.id()).join());
    assertNull(value("SELECT acknowledged_at FROM pending_notifications"));
    assertTrue(service.healthy());
  }

  @Test
  void warningExpirationStopsNotificationWithoutRemovingHistory() {
    JsonObject payload = reason();
    payload.addProperty("expiresAt", clock.instant().plusSeconds(30).toString());
    applied(create(ActionType.CREATE_WARNING, payload));
    clock.advance(31);
    assertTrue(service.pendingWarnings(player).join().isEmpty());
    service.expire().join();
    assertEquals(1, count("punishments"));
  }

  @Test
  void kickClaimIsExclusiveAndExecutionIsHistorical() {
    Punishment kick = applied(create(ActionType.CREATE_KICK, reason())).punishment();
    assertEquals(Status.COMPLETED, kick.status());
    assertTrue(service.active(player, Type.KICK).isEmpty());
    CompletableFuture<Boolean> first = service.claimKick(kick.id());
    CompletableFuture<Boolean> second = service.claimKick(kick.id());
    assertTrue(first.join());
    assertFalse(second.join());
    assertTrue(service.pendingKicks().isEmpty());
    service.markKick(kick.id(), "EXECUTED").join();
    service.markKick(kick.id(), "FAILED").join();
    assertEquals("EXECUTED", value("SELECT delivery_state FROM pending_notifications"));
    assertEquals(3, count("punishment_events"));
  }

  @Test
  void offlineKickFailureAndExpiredKickCannotExecuteLater() {
    Punishment offline = applied(create(ActionType.CREATE_KICK, reason())).punishment();
    service.markKick(offline.id(), "FAILED").join();
    Punishment delayed = applied(create(ActionType.CREATE_KICK, reason())).punishment();
    clock.advance(31);
    assertFalse(service.claimKick(delayed.id()).join());
    assertEquals(
        "EXPIRED",
        value(
            "SELECT delivery_state FROM pending_notifications WHERE punishment_id='"
                + delayed.id()
                + "'"));
    assertEquals(0, service.pendingKicks().size());
  }

  @Test
  void crashAfterClaimIsFailedWithoutReplayingKick() throws Exception {
    Punishment kick = applied(create(ActionType.CREATE_KICK, reason())).punishment();
    assertTrue(service.claimKick(kick.id()).join());
    restart();
    service.recoverInterruptedKicks().join();
    assertEquals("FAILED", value("SELECT delivery_state FROM pending_notifications"));
    assertFalse(service.claimKick(kick.id()).join());
  }

  @Test
  void remoteKickRequiresBoundedFreshDeadline() {
    Action noDeadline =
        new Action(
            UUID.randomUUID(),
            ActionType.CREATE_KICK,
            player,
            "Steve",
            null,
            null,
            "staff:1",
            Source.WEBSITE,
            clock.instant(),
            null,
            reason());
    assertEquals("INVALID_KICK_DEADLINE", service.apply(noDeadline).join().code());
    Action stale =
        new Action(
            UUID.randomUUID(),
            ActionType.CREATE_KICK,
            player,
            "Steve",
            null,
            null,
            "staff:1",
            Source.WEBSITE,
            clock.instant().minusSeconds(100),
            clock.instant().minusSeconds(1),
            reason());
    assertEquals("ACTION_EXPIRED", service.apply(stale).join().code());
    Action hours =
        new Action(
            UUID.randomUUID(),
            ActionType.CREATE_KICK,
            player,
            "Steve",
            null,
            null,
            "staff:1",
            Source.WEBSITE,
            clock.instant(),
            clock.instant().plusSeconds(3600),
            reason());
    assertEquals("INVALID_KICK_DEADLINE", service.apply(hours).join().code());
    assertEquals(0, count("punishments"));
  }

  @Test
  void communityServiceAssignmentModificationAndReleaseRetainRecoveryUntilConfirmed() {
    JsonObject work = reason();
    work.addProperty("blocks", 250);
    work.addProperty("tool", "STONE_PICKAXE");
    Punishment servicePunishment =
        applied(create(ActionType.CREATE_COMMUNITY_SERVICE, work)).punishment();
    assertEquals("ASSIGNED", service.sentence(player).orElseThrow().state());
    assertEquals(
        "ACTIVE_SENTENCE_EXISTS",
        service.apply(create(ActionType.CREATE_COMMUNITY_SERVICE, work)).join().code());
    JsonObject add = reason();
    add.addProperty("blocks", 50);
    Action increase = modify(ActionType.ADD_SERVICE_BLOCKS, servicePunishment, add);
    Punishment increased = applied(increase).punishment();
    service.apply(increase).join();
    assertEquals(300, service.sentence(player).orElseThrow().required());
    applied(modify(ActionType.RELEASE_SERVICE, increased, reason()));
    assertEquals("WORK_COMPLETE", service.sentence(player).orElseThrow().state());
    assertEquals(
        "ACTIVE_SENTENCE_EXISTS",
        service.apply(create(ActionType.CREATE_COMMUNITY_SERVICE, work)).join().code());
  }

  @Test
  void sentenceReductionCompletesWorkWithoutMarkingRestorationCompleted() {
    JsonObject work = reason();
    work.addProperty("blocks", 250);
    Punishment punishment = applied(create(ActionType.CREATE_COMMUNITY_SERVICE, work)).punishment();
    database
        .write(
            connection -> {
              try (var sql = connection.createStatement()) {
                sql.executeUpdate("UPDATE community_service SET completed=100,state='ACTIVE'");
              }
              return null;
            })
        .join();
    JsonObject reduction = reason();
    reduction.addProperty("blocks", 90);
    applied(modify(ActionType.SET_SERVICE_BLOCKS, punishment, reduction));
    assertEquals("WORK_COMPLETE", service.sentence(player).orElseThrow().state());
    assertEquals(Status.ACTIVE, service.find(punishment.id()).orElseThrow().status());
  }

  @Test
  void sentenceExpiryTransitionsToRestorePending() {
    JsonObject work = reason();
    work.addProperty("blocks", 250);
    work.addProperty("expiresAt", clock.instant().plusSeconds(10).toString());
    applied(create(ActionType.CREATE_COMMUNITY_SERVICE, work));
    clock.advance(10);
    service.expire().join();
    assertEquals("WORK_COMPLETE", service.sentence(player).orElseThrow().state());
    assertTrue(service.active(player, Type.COMMUNITY_SERVICE).isEmpty());
  }

  @Test
  void unverifiedAndAmbiguousNamesAreRejectedButUuidIsStable() {
    assertTrue(service.resolve("Steve").join().isEmpty());
    // A punishment's website-supplied display name is not proof that it identifies an account.
    applied(create(ActionType.CREATE_BAN, reason()));
    assertTrue(service.resolve("Steve").join().isEmpty());
    service.rememberPlayer(player, "Steve").join();
    assertEquals(player, service.resolve("sTeVe").join().orElseThrow());
    service.rememberPlayer(UUID.randomUUID(), "Steve").join();
    assertTrue(service.resolve("Steve").join().isEmpty());
    assertEquals(player, service.resolve(player.toString()).join().orElseThrow());
    assertTrue(service.resolve("1-1-1-1-1").join().isEmpty());
  }

  @Test
  void failedTransactionRollsBackDecisionLedgerAuditAndOutboxAndInvalidatesCache() {
    Action action = create(ActionType.CREATE_BAN, reason());
    assertThrows(
        CompletionException.class,
        () ->
            database
                .write(
                    connection -> {
                      service.apply(connection, action);
                      throw new SQLException("simulated disk failure");
                    })
                .join());
    assertEquals(0, count("punishments"));
    assertEquals(0, count("punishment_events"));
    assertEquals(0, count("sync_outbox"));
    assertEquals(0, count("local_actions"));
    assertFalse(service.healthy());
    assertTrue(service.active(player, Type.BAN).isEmpty());
    service.refresh().join();
    assertTrue(service.healthy());
    applied(action);
  }

  @Test
  void successfulUnrelatedWriteDoesNotClearAStaleCacheFailure() {
    applied(create(ActionType.CREATE_BAN, reason()));
    assertThrows(
        CompletionException.class,
        () ->
            database
                .write(
                    connection -> {
                      throw new SQLException("disk unavailable");
                    })
                .join());
    service.rememberPlayer(player, "Steve").join();
    assertFalse(service.healthy());
    assertTrue(service.active(player, Type.BAN).isPresent());
    service.refresh().join();
    assertTrue(service.healthy());
  }

  @Test
  void appealTokensStayPrivateAndRotationUsesRevision() {
    String url = "https://www.mineplay.co.uk/appeal/0123456789abcdefghijklmnopqrstuvwxyzABCDEFG";
    JsonObject payload = reason();
    payload.addProperty("appealUrl", url);
    Punishment ban = applied(create(ActionType.CREATE_BAN, payload)).punishment();
    assertEquals(url, ban.appealUrl());
    assertEquals(0, count("appeal_requests"));
    assertFalse(value("SELECT payload FROM sync_outbox LIMIT 1").contains("0123456789"));
    JsonObject rotate = new JsonObject();
    rotate.addProperty(
        "appealUrl",
        "https://www.mineplay.co.uk/appeal/abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG");
    Punishment updated = applied(modify(ActionType.ROTATE_APPEAL_TOKEN, ban, rotate)).punishment();
    assertNotEquals(ban.appealUrl(), updated.appealUrl());
    assertEquals(
        "REVISION_CONFLICT",
        service.apply(modify(ActionType.ROTATE_APPEAL_TOKEN, ban, rotate)).join().code());
    assertFalse(
        database
            .read(
                connection -> {
                  try (var query = connection.createStatement();
                      var rows = query.executeQuery("SELECT payload FROM sync_outbox")) {
                    StringBuilder all = new StringBuilder();
                    while (rows.next()) all.append(rows.getString(1));
                    return all.toString();
                  }
                })
            .join()
            .contains("/appeal/"));
  }

  @Test
  void pendingAppealRequestSurvivesRestartAndProvisioningCompletesIt() throws Exception {
    Punishment ban = applied(create(ActionType.CREATE_BAN, reason())).punishment();
    String provisioningId = value("SELECT provisioning_id FROM appeal_requests");
    restart();
    assertEquals(provisioningId, value("SELECT provisioning_id FROM appeal_requests"));
    JsonObject link = new JsonObject();
    link.addProperty(
        "appealUrl",
        "https://www.mineplay.co.uk/appeal/abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG");
    applied(modify(ActionType.ROTATE_APPEAL_TOKEN, ban, link));
    assertEquals("COMPLETE", value("SELECT state FROM appeal_requests"));
  }

  @Test
  void invalidReasonsDurationsBlocksToolsAndAppealOriginsAreRejectedWithoutMutation() {
    JsonObject blank = reason();
    blank.addProperty("reason", "\n");
    assertFalse(service.apply(create(ActionType.CREATE_BAN, blank)).join().applied());
    JsonObject expired = reason();
    expired.addProperty("expiresAt", clock.instant().toString());
    assertEquals(
        "INVALID_EXPIRATION", service.apply(create(ActionType.CREATE_BAN, expired)).join().code());
    JsonObject fractional = reason();
    fractional.addProperty("blocks", 25.1);
    assertEquals(
        "INVALID_BLOCKS",
        service.apply(create(ActionType.CREATE_COMMUNITY_SERVICE, fractional)).join().code());
    JsonObject tool = reason();
    tool.addProperty("blocks", 25);
    tool.addProperty("tool", "DIAMOND_PICKAXE");
    assertEquals(
        "INVALID_TOOL",
        service.apply(create(ActionType.CREATE_COMMUNITY_SERVICE, tool)).join().code());
    JsonObject malicious = reason();
    malicious.addProperty(
        "appealUrl", "https://evil.example/appeal/abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG");
    assertEquals(
        "INVALID_APPEAL_URL",
        service.apply(create(ActionType.CREATE_BAN, malicious)).join().code());
    assertEquals(0, count("punishments"));
    assertEquals(0, count("punishment_events"));
  }

  @Test
  void configuredLimitsApplyToWebsiteAndLocalDecisions() {
    service.configureLimits(20, 500, 30);
    JsonObject tooSmall = reason();
    tooSmall.addProperty("blocks", 19);
    assertEquals(
        "INVALID_BLOCKS",
        service.apply(create(ActionType.CREATE_COMMUNITY_SERVICE, tooSmall)).join().code());
    JsonObject work = reason();
    work.addProperty("blocks", 490);
    Punishment punishment = applied(create(ActionType.CREATE_COMMUNITY_SERVICE, work)).punishment();
    JsonObject add = reason();
    add.addProperty("blocks", 20);
    assertEquals(
        "INVALID_BLOCKS",
        service.apply(modify(ActionType.ADD_SERVICE_BLOCKS, punishment, add)).join().code());
    assertEquals(490, service.sentence(player).orElseThrow().required());
  }

  @Test
  void repeatedConcurrentAssignmentsHaveOneWinner() {
    CompletableFuture<Result> first = service.apply(create(ActionType.CREATE_BAN, reason()));
    CompletableFuture<Result> second = service.apply(create(ActionType.CREATE_BAN, reason()));
    assertEquals(1, List.of(first.join(), second.join()).stream().filter(Result::applied).count());
    assertEquals(1, count("punishments"));
  }

  @Test
  void actionPayloadIsImmutableAcrossAsyncSubmission() {
    JsonObject payload = reason();
    Action action = create(ActionType.CREATE_BAN, payload);
    payload.addProperty("reason", "tampered outside");
    action.payload().addProperty("reason", "tampered accessor");
    assertEquals("Test moderation reason", applied(action).punishment().reason());
  }

  @Test
  void replacementBanExpiresOldRecordAndCommitsBothEventsAtomically() {
    JsonObject timed = reason();
    timed.addProperty("expiresAt", clock.instant().plusSeconds(30).toString());
    applied(create(ActionType.CREATE_BAN, timed));
    clock.advance(31);
    Punishment replacement = applied(create(ActionType.CREATE_BAN, reason())).punishment();
    assertEquals(replacement, service.active(player, Type.BAN).orElseThrow());
    assertEquals(2, count("punishments"));
    assertEquals(3, count("punishment_events"));
  }

  @Test
  void disabledTypeStopsNewDecisionsButAllowsExistingPunishmentRelease() {
    Punishment ban = applied(create(ActionType.CREATE_BAN, reason())).punishment();
    service.configureTypeEnabled(Type.BAN, false);
    assertEquals(
        "TYPE_DISABLED", service.apply(create(ActionType.CREATE_BAN, reason())).join().code());
    assertTrue(service.active(player, Type.BAN).isPresent());
    applied(modify(ActionType.REVOKE_PUNISHMENT, ban, reason()));
  }

  @Test
  void configuredAppealOriginAndPathAreEnforcedForAllActions() {
    service.configureAppealBase("https://staging.mineplay.co.uk:9443/secure/appeal/");
    String token = "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG";
    JsonObject wrong = reason();
    wrong.addProperty("appealUrl", "https://www.mineplay.co.uk/appeal/" + token);
    assertEquals(
        "INVALID_APPEAL_URL", service.apply(create(ActionType.CREATE_BAN, wrong)).join().code());
    JsonObject valid = reason();
    valid.addProperty("appealUrl", "https://staging.mineplay.co.uk:9443/secure/appeal/" + token);
    applied(create(ActionType.CREATE_BAN, valid));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.configureAppealBase("http://www.mineplay.co.uk/appeal/"));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.configureAppealBase("https://www.mineplay.co.uk/appeal/?token=oops"));
  }

  @Test
  void committedExternalActionIsFailClosedUntilCacheRefresh() {
    Action action = create(ActionType.CREATE_BAN, reason());
    database.write(connection -> service.apply(connection, action)).join();
    assertFalse(service.healthy());
    service.refresh().join();
    assertTrue(service.healthy());
    assertTrue(service.active(player, Type.BAN).isPresent());
  }

  @Test
  void staffDecisionTimestampIsRetainedSeparatelyFromLocalCommitTime() {
    Instant decisionTime = clock.instant().minusSeconds(300);
    Action action =
        new Action(
            UUID.randomUUID(),
            ActionType.CREATE_BAN,
            player,
            "Steve",
            null,
            null,
            "staff:website",
            Source.WEBSITE,
            decisionTime,
            clock.instant().plusSeconds(60),
            reason());
    assertEquals(decisionTime, applied(action).punishment().issuedAt());
    assertEquals(clock.instant().toString(), value("SELECT created_at FROM punishment_events"));
  }

  private Result applied(Action action) {
    Result result = service.apply(action).join();
    assertTrue(result.applied(), result.code() + ": " + result.detail());
    return result;
  }

  private Action create(ActionType type, JsonObject payload) {
    return new Action(
        UUID.randomUUID(),
        type,
        player,
        "Steve",
        null,
        null,
        "Console",
        Source.CONSOLE,
        clock.instant(),
        null,
        payload);
  }

  private Action modify(ActionType type, Punishment punishment, JsonObject payload) {
    return new Action(
        UUID.randomUUID(),
        type,
        player,
        "Steve",
        punishment.id(),
        punishment.revision(),
        "Console",
        Source.CONSOLE,
        clock.instant(),
        null,
        payload);
  }

  private JsonObject reason() {
    JsonObject json = new JsonObject();
    json.addProperty("reason", "Test moderation reason");
    return json;
  }

  private int count(String table) {
    return Integer.parseInt(value("SELECT count(*) FROM " + table));
  }

  private String value(String query) {
    return database
        .read(
            connection -> {
              try (var sql = connection.createStatement();
                  var rows = sql.executeQuery(query)) {
                return rows.next() ? rows.getString(1) : null;
              }
            })
        .join();
  }

  private void restart() throws Exception {
    database.close();
    database = new Database(directory.resolve("punishments.db"));
    database.initialize();
    service = new PunishmentService(database, "test-smp", clock);
    service.refresh().join();
  }

  private static final class MutableClock extends Clock {
    private Instant now;

    private MutableClock(Instant now) {
      this.now = now;
    }

    private void advance(long seconds) {
      now = now.plusSeconds(seconds);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
