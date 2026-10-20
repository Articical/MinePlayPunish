package cloud.mineplay.punishments.integration;

import static org.junit.jupiter.api.Assertions.*;

import cloud.mineplay.punishments.appeals.AppealLinks;
import cloud.mineplay.punishments.punishment.Models.*;
import cloud.mineplay.punishments.punishment.PunishmentService;
import cloud.mineplay.punishments.storage.Database;
import com.google.gson.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class RemoteActionProcessorTest {
  @TempDir Path temp;
  Database db;
  PunishmentService service;
  RemoteActionProcessor processor;
  static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");
  static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  @BeforeEach
  void setup() throws Exception {
    db = new Database(temp.resolve("punishments.db"));
    db.initialize();
    service = new PunishmentService(db, "test-server", CLOCK);
    service.refresh().join();
    processor =
        new RemoteActionProcessor(
            db,
            service,
            "test-server",
            CLOCK,
            new AppealLinks("https://www.mineplay.co.uk/appeal/"),
            () -> true,
            30);
    processor.initialize().join();
  }

  @AfterEach
  void close() throws Exception {
    db.close();
  }

  static JsonObject action(String type, UUID target, UUID punishment, Long revision) {
    JsonObject action = new JsonObject();
    action.addProperty("actionId", UUID.randomUUID().toString());
    action.addProperty("type", type);
    action.addProperty("punishmentId", punishment.toString());
    action.addProperty("staffId", "staff:42");
    action.addProperty("issuedAt", NOW.toString());
    action.addProperty("validUntil", NOW.plusSeconds(3600).toString());
    if (revision != null) action.addProperty("expectedRevision", revision);
    JsonObject player = new JsonObject();
    player.addProperty("uuid", target.toString());
    player.addProperty("lastKnownName", "TestPlayer");
    action.add("target", player);
    JsonObject payload = new JsonObject();
    payload.addProperty("reason", "Moderation test");
    action.add("payload", payload);
    return action;
  }

  void accept(JsonObject... actions) {
    JsonArray array = new JsonArray();
    for (JsonObject action : actions) array.add(action);
    processor.accept(array, "cursor-1", "snapshot-1").join();
  }

  long count(String table) {
    return db.read(
            c -> {
              try (var sql = c.createStatement();
                  var rs = sql.executeQuery("SELECT COUNT(*) FROM " + table)) {
                return rs.getLong(1);
              }
            })
        .join();
  }

  String cursor() {
    return db.read(
            c -> {
              try (var sql = c.createStatement();
                  var rs = sql.executeQuery("SELECT remote_cursor FROM sync_state")) {
                return rs.getString(1);
              }
            })
        .join();
  }

  @Test
  void duplicateRemoteBanCommitsOnlyOnceAndQueuesStableAcknowledgement() {
    UUID target = UUID.randomUUID(), punishment = UUID.randomUUID();
    JsonObject wire = action("CREATE_BAN", target, punishment, null);
    accept(wire);
    accept(wire.deepCopy());
    assertEquals(1, count("punishments"));
    assertEquals(1, count("remote_inbox"));
    assertEquals(1, count("remote_acknowledgements"));
    assertEquals(1, service.active(target, Type.BAN).orElseThrow().revision());
    assertEquals("cursor-1", cursor());
  }

  @Test
  void changedReplayCannotMutateOrAdvanceCursor() {
    JsonObject wire = action("CREATE_BAN", UUID.randomUUID(), UUID.randomUUID(), null);
    accept(wire);
    wire.getAsJsonObject("payload").addProperty("reason", "changed");
    JsonArray page = new JsonArray();
    page.add(wire);
    assertThrows(
        CompletionException.class, () -> processor.accept(page, "cursor-2", "snapshot-2").join());
    assertEquals("cursor-1", cursor());
    assertEquals(1, count("punishments"));
  }

  @Test
  void malformedUuidRollsBackWholePage() {
    JsonObject valid = action("CREATE_BAN", UUID.randomUUID(), UUID.randomUUID(), null);
    JsonObject invalid = action("CREATE_MUTE", UUID.randomUUID(), UUID.randomUUID(), null);
    invalid.addProperty("actionId", "1-1-1-1-1");
    assertThrows(CompletionException.class, () -> accept(valid, invalid));
    assertEquals(0, count("punishments"));
    assertEquals("", cursor());
  }

  @Test
  void failedInboxInsertRollsBackPunishmentOutboxAndCursor() {
    db.write(
            c -> {
              try (var sql = c.createStatement()) {
                sql.execute(
                    "CREATE TRIGGER test_fail_inbox BEFORE INSERT ON remote_inbox BEGIN SELECT"
                        + " RAISE(ABORT,'test failure'); END");
              }
              return null;
            })
        .join();
    assertThrows(
        CompletionException.class,
        () -> accept(action("CREATE_BAN", UUID.randomUUID(), UUID.randomUUID(), null)));
    assertEquals(0, count("punishments"));
    assertEquals(0, count("sync_outbox"));
    assertEquals("", cursor());
  }

  @Test
  void acceptedAppealUnbansOnlyExpectedRevision() {
    UUID target = UUID.randomUUID(), punishment = UUID.randomUUID();
    accept(action("CREATE_BAN", target, punishment, null));
    accept(action("REVOKE_PUNISHMENT", target, punishment, 1L));
    assertTrue(service.active(target, Type.BAN).isEmpty());
    assertEquals(Status.REVOKED, service.find(punishment).orElseThrow().status());
  }

  @Test
  void staleRevisionDoesNotOverwriteNewerLocalDecision() {
    UUID target = UUID.randomUUID(), punishment = UUID.randomUUID();
    accept(action("CREATE_BAN", target, punishment, null));
    JsonObject change = action("MODIFY_EXPIRATION", target, punishment, 1L);
    change.getAsJsonObject("payload").addProperty("expiresAt", NOW.plusSeconds(7200).toString());
    accept(change);
    accept(action("REVOKE_PUNISHMENT", target, punishment, 1L));
    assertEquals(2, service.active(target, Type.BAN).orElseThrow().revision());
  }

  @Test
  void deniedAppealCannotChangePunishment() {
    UUID target = UUID.randomUUID(), punishment = UUID.randomUUID();
    accept(action("CREATE_BAN", target, punishment, null));
    accept(action("DENY_APPEAL", target, punishment, 1L));
    assertTrue(service.active(target, Type.BAN).isPresent());
    assertEquals(1, service.find(punishment).orElseThrow().revision());
  }

  @Test
  void outOfOrderRevokeIsRejectedRatherThanRememberedAsFutureInstruction() {
    UUID target = UUID.randomUUID(), punishment = UUID.randomUUID();
    accept(
        action("REVOKE_PUNISHMENT", target, punishment, 1L),
        action("CREATE_BAN", target, punishment, null));
    assertTrue(service.active(target, Type.BAN).isPresent());
    assertEquals(2, count("remote_inbox"));
  }

  @Test
  void serviceAddIsNotDoubledByDuplicateDelivery() {
    UUID target = UUID.randomUUID(), punishment = UUID.randomUUID();
    JsonObject create = action("CREATE_COMMUNITY_SERVICE", target, punishment, null);
    create.getAsJsonObject("payload").addProperty("blocks", 250);
    create.getAsJsonObject("payload").addProperty("tool", "WOODEN_PICKAXE");
    accept(create);
    JsonObject add = action("ADD_SERVICE_BLOCKS", target, punishment, 1L);
    add.getAsJsonObject("payload").addProperty("blocks", 50);
    accept(add);
    accept(add);
    assertEquals(300, service.sentence(target).orElseThrow().required());
  }

  @Test
  void staleRemoteKickDoesNotBecomeDeferredLoginKick() {
    JsonObject kick = action("CREATE_KICK", UUID.randomUUID(), UUID.randomUUID(), null);
    kick.addProperty("issuedAt", NOW.minusSeconds(60).toString());
    accept(kick);
    assertEquals(0, count("punishments"));
  }

  @Test
  void unsafeQuarryRejectsRemoteAssignment() {
    processor =
        new RemoteActionProcessor(
            db,
            service,
            "test-server",
            CLOCK,
            new AppealLinks("https://www.mineplay.co.uk/appeal/"),
            () -> false,
            30);
    JsonObject create =
        action("CREATE_COMMUNITY_SERVICE", UUID.randomUUID(), UUID.randomUUID(), null);
    create.getAsJsonObject("payload").addProperty("blocks", 250);
    accept(create);
    assertEquals(0, count("community_service"));
  }

  @Test
  void remoteTokensNeverEnterPublicInboxOutcomeOrEvents() {
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
    JsonObject create = action("CREATE_BAN", UUID.randomUUID(), UUID.randomUUID(), null);
    create
        .getAsJsonObject("payload")
        .addProperty("appealUrl", "https://www.mineplay.co.uk/appeal/" + token);
    accept(create);
    db.read(
            c -> {
              for (String query :
                  List.of(
                      "SELECT payload FROM sync_outbox",
                      "SELECT payload FROM punishment_events",
                      "SELECT result_json FROM remote_inbox")) {
                try (var sql = c.createStatement();
                    var rs = sql.executeQuery(query)) {
                  while (rs.next()) assertFalse(rs.getString(1).contains(token));
                }
              }
              return null;
            })
        .join();
  }

  @Test
  void strictValidationRejectsFractionalBlocksAndArbitraryCommands() {
    JsonObject wire =
        action("CREATE_COMMUNITY_SERVICE", UUID.randomUUID(), UUID.randomUUID(), null);
    wire.getAsJsonObject("payload").addProperty("blocks", 1.5);
    assertThrows(IllegalArgumentException.class, () -> processor.decode(wire));
    JsonObject other = action("CREATE_BAN", UUID.randomUUID(), UUID.randomUUID(), null);
    other.getAsJsonObject("payload").addProperty("command", "op somebody");
    assertThrows(IllegalArgumentException.class, () -> processor.decode(other));
  }
}
