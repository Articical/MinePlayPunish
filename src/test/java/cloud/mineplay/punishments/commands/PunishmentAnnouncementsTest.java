package cloud.mineplay.punishments.commands;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import cloud.mineplay.punishments.MineplayPunishments;
import cloud.mineplay.punishments.config.Messages;
import cloud.mineplay.punishments.punishment.Models.*;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.MockedStatic;

class PunishmentAnnouncementsTest {
  private static final UUID TARGET = UUID.fromString("d9a1934f-7df6-4055-9382-efbf37680513");
  private static final Instant ISSUED = Instant.parse("2026-10-20T12:00:00Z");
  private static final String APPEAL_SECRET = "private-appeal-token-never-for-public-chat";
  @TempDir Path folder;
  private final List<Component> broadcasts = new ArrayList<>();
  private MockedStatic<Bukkit> bukkit;
  private PunishmentAnnouncements announcements;

  @BeforeEach
  void setUp() throws Exception {
    try (var stream = getClass().getResourceAsStream("/messages.yml")) {
      Files.copy(stream, folder.resolve("messages.yml"));
    }
    MineplayPunishments plugin = mock(MineplayPunishments.class);
    when(plugin.getDataFolder()).thenReturn(folder.toFile());
    Messages messages = new Messages(plugin);
    when(plugin.messages()).thenReturn(messages);
    announcements = new PunishmentAnnouncements(plugin);
    bukkit = mockStatic(Bukkit.class);
    bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
    bukkit
        .when(() -> Bukkit.broadcast(any(Component.class)))
        .thenAnswer(
            call -> {
              broadcasts.add(call.getArgument(0));
              return 1;
            });
  }

  @AfterEach
  void tearDown() {
    if (bukkit != null) bukkit.close();
  }

  @Test
  void appliedActionBroadcastsOnceAcrossRepeatedCompletionCallbacks() {
    Action action = action(ActionType.CREATE_WARNING, "Repeated misconduct");
    Result result = applied(punishment(Type.WARNING, "Target", "Repeated misconduct", null));
    announcements.announce(action, result, "Moderator", false);
    announcements.announce(action, result, "Moderator", false);

    assertEquals(1, broadcasts.size());
    assertEquals(
        "Mineplay » Moderator issued a warning to Target. Reason: Repeated misconduct [MP-TEST]",
        plain(broadcasts.getFirst()));
  }

  @Test
  void distinctActionsOnTheSamePunishmentAreAnnouncedIndependently() {
    Result result = applied(punishment(Type.WARNING, "Target", "Conduct", null));
    announcements.announce(action(ActionType.CREATE_WARNING, "Conduct"), result, "Staff", false);
    announcements.announce(
        action(ActionType.REVOKE_PUNISHMENT, "Appeal upheld"), result, "Staff", false);

    assertEquals(2, broadcasts.size());
    assertTrue(plain(broadcasts.getLast()).contains("revoked the warning for Target"));
  }

  @Test
  void failedOrMissingResultsDoNotBroadcastOrConsumeTheAction() {
    Action action = action(ActionType.CREATE_WARNING, "Conduct");
    Punishment punishment = punishment(Type.WARNING, "Target", "Conduct", null);
    announcements.announce(null, applied(punishment), "Staff", false);
    announcements.announce(action, null, "Staff", false);
    announcements.announce(
        action, new Result(false, "CONFLICT", "Not applied", punishment), "Staff", false);
    announcements.announce(action, new Result(true, "APPLIED", "No record", null), "Staff", false);
    assertTrue(broadcasts.isEmpty());

    announcements.announce(action, applied(punishment), "Staff", false);
    assertEquals(1, broadcasts.size());
  }

  @Test
  void silentAppliedActionRemainsSilentOnDuplicatePublicCallback() {
    Action action = action(ActionType.CREATE_BAN, "Conduct");
    Result result = applied(punishment(Type.BAN, "Target", "Conduct", null));
    announcements.announce(action, result, "Staff", true);
    announcements.announce(action, result, "Staff", false);
    assertTrue(broadcasts.isEmpty());

    announcements.announce(
        action(ActionType.REVOKE_PUNISHMENT, "Appeal upheld"), result, "Staff", false);
    assertEquals(1, broadcasts.size());
  }

  @Test
  void appealRotationNeverPublishesPrivateProvisioningWork() {
    Action action = action(ActionType.ROTATE_APPEAL_TOKEN, null);
    Result result = applied(punishment(Type.BAN, "Target", "Conduct", null));
    announcements.announce(action, result, "Website", false);
    announcements.announce(action, result, "Website", false);
    assertTrue(broadcasts.isEmpty());
  }

  @Test
  void actorTargetAndReasonAreLiteralTextAndCannotAddMiniMessageEvents() {
    String actor = "<click:run_command:'/op attacker'>Moderator</click>";
    String target = "<red>Target</red>";
    String reason =
        "<hover:show_text:'secret'>Reason</hover>"
            + " <click:open_url:'https://example.test'>link</click>";
    announcements.announce(
        action(ActionType.CREATE_WARNING, reason),
        applied(punishment(Type.WARNING, target, "Original reason", null)),
        actor,
        false);

    assertEquals(1, broadcasts.size());
    String rendered = plain(broadcasts.getFirst());
    assertTrue(rendered.contains(actor));
    assertTrue(rendered.contains(target));
    assertTrue(rendered.contains(reason));
    assertNoEvents(broadcasts.getFirst());
  }

  @Test
  void publicAnnouncementIncludesReferenceButNeverAppealCredentials() {
    announcements.announce(
        action(ActionType.CREATE_BAN, "Conduct"),
        applied(punishment(Type.BAN, "Target", "Conduct", null)),
        "Staff",
        false);

    Component message = broadcasts.getFirst();
    assertTrue(plain(message).contains("[MP-TEST]"));
    assertFalse(message.toString().contains(APPEAL_SECRET));
    assertFalse(message.toString().contains("https://www.mineplay.co.uk"));
    assertNoEvents(message);
  }

  @ParameterizedTest
  @EnumSource(
      value = ActionType.class,
      names = {"REVOKE_PUNISHMENT", "RELEASE_SERVICE", "MODIFY_EXPIRATION"})
  void laterActionUsesItsOwnReasonInsteadOfOriginalOffence(ActionType type) {
    announcements.announce(
        action(type, "Appeal accepted after review"),
        applied(punishment(Type.COMMUNITY_SERVICE, "Target", "Original offence", null)),
        "Staff",
        false);

    assertTrue(plain(broadcasts.getFirst()).contains("Reason: Appeal accepted after review"));
    assertFalse(plain(broadcasts.getFirst()).contains("Original offence"));
  }

  @Test
  void absentActionReasonFallsBackToThePersistedReason() {
    announcements.announce(
        action(ActionType.CREATE_KICK, null),
        applied(punishment(Type.KICK, "Target", "Persisted reason", null)),
        "Staff",
        false);
    assertTrue(
        plain(broadcasts.getFirst()).contains("issued a kick to Target. Reason: Persisted reason"));
  }

  @ParameterizedTest
  @CsvSource({
    "CREATE_BAN,BAN,0,a permanent ban",
    "CREATE_MUTE,MUTE,0,a permanent mute",
    "CREATE_BAN,BAN,86400,a 1d ban",
    "CREATE_MUTE,MUTE,7200,a 2h mute",
    "CREATE_BAN,BAN,1800,a 30m ban",
    "CREATE_MUTE,MUTE,90,a 90s mute"
  })
  void temporaryAndPermanentPunishmentsStateTheirDuration(
      ActionType actionType, Type type, long seconds, String expected) {
    announcements.announce(
        action(actionType, "Conduct"),
        applied(
            punishment(
                type, "Target", "Conduct", seconds == 0 ? null : ISSUED.plusSeconds(seconds))),
        "Staff",
        false);
    assertTrue(plain(broadcasts.getFirst()).contains("issued " + expected + " to Target"));
  }

  @Test
  void subsecondGapBetweenDurationAndActionCreationStillDisplaysOneHour() {
    announcements.announce(
        action(ActionType.CREATE_BAN, "Conduct"),
        applied(punishment(Type.BAN, "Target", "Conduct", ISSUED.plusSeconds(3600).minusMillis(5))),
        "Staff",
        false);
    assertTrue(plain(broadcasts.getFirst()).contains("issued a 1h ban to Target"));
  }

  @ParameterizedTest
  @CsvSource({
    "CREATE_COMMUNITY_SERVICE,assigned 125 community service blocks to",
    "ADD_SERVICE_BLOCKS,added 125 community service blocks for",
    "SET_SERVICE_BLOCKS,set community service to 125 blocks for"
  })
  void serviceAnnouncementsUseTheCommittedActionBlockCount(ActionType type, String description) {
    announcements.announce(
        action(type, "Conduct"),
        applied(punishment(Type.COMMUNITY_SERVICE, "Target", "Conduct", null)),
        "Staff",
        false);
    assertTrue(plain(broadcasts.getFirst()).contains(description + " Target"));
  }

  @Test
  void missingDisplayNamesUsePersistedIssuerAndTargetUuid() {
    for (String missing : new String[] {null, "  "}) {
      announcements.announce(
          action(ActionType.CREATE_WARNING, "Conduct"),
          applied(punishment(Type.WARNING, missing, "Conduct", null)),
          missing,
          false);
    }
    assertEquals(2, broadcasts.size());
    for (Component message : broadcasts)
      assertTrue(plain(message).contains("Console issued a warning to " + TARGET));
  }

  @Test
  void asynchronousCallsAreRejectedBeforeBroadcast() {
    bukkit.when(Bukkit::isPrimaryThread).thenReturn(false);
    assertThrows(
        IllegalStateException.class,
        () ->
            announcements.announce(
                action(ActionType.CREATE_WARNING, "Conduct"),
                applied(punishment(Type.WARNING, "Target", "Conduct", null)),
                "Staff",
                false));
    assertTrue(broadcasts.isEmpty());
  }

  private static Action action(ActionType type, String reason) {
    JsonObject payload = new JsonObject();
    if (reason != null) payload.addProperty("reason", reason);
    if (type == ActionType.CREATE_COMMUNITY_SERVICE
        || type == ActionType.ADD_SERVICE_BLOCKS
        || type == ActionType.SET_SERVICE_BLOCKS) payload.addProperty("blocks", 125);
    return new Action(
        UUID.randomUUID(),
        type,
        TARGET,
        "Target",
        null,
        null,
        "Console",
        Source.CONSOLE,
        ISSUED,
        null,
        payload);
  }

  private static Punishment punishment(
      Type type, String targetName, String reason, Instant expires) {
    return new Punishment(
        UUID.randomUUID(),
        "MP-TEST",
        TARGET,
        targetName,
        type,
        reason,
        "Original issuer",
        Source.IN_GAME,
        ISSUED,
        expires,
        Status.ACTIVE,
        1,
        "https://www.mineplay.co.uk/appeal/" + APPEAL_SECRET);
  }

  private static Result applied(Punishment punishment) {
    return new Result(true, "APPLIED", "Stored successfully", punishment);
  }

  private static String plain(Component message) {
    return PlainTextComponentSerializer.plainText().serialize(message);
  }

  private static void assertNoEvents(Component message) {
    assertNull(message.clickEvent());
    assertNull(message.hoverEvent());
    for (Component child : message.children()) assertNoEvents(child);
  }
}
