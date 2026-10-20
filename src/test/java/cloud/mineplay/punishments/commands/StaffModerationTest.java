package cloud.mineplay.punishments.commands;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cloud.mineplay.punishments.MineplayPunishments;
import cloud.mineplay.punishments.communityservice.CommunityServiceManager;
import cloud.mineplay.punishments.config.Messages;
import cloud.mineplay.punishments.config.PluginSettings;
import cloud.mineplay.punishments.enforcement.EnforcementListener;
import cloud.mineplay.punishments.integration.SyncClient;
import cloud.mineplay.punishments.permissions.StaffHierarchy;
import cloud.mineplay.punishments.punishment.Models.*;
import cloud.mineplay.punishments.punishment.PunishmentService;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

class StaffModerationTest {
  private final UUID actorId = UUID.randomUUID();
  private final UUID targetId = UUID.randomUUID();
  private final Instant now = Instant.parse("2026-10-18T18:00:00Z");
  private MineplayPunishments plugin;
  private Player actor;
  private PunishmentService store;
  private CommunityServiceManager community;
  private EnforcementListener enforcement;
  private SyncClient sync;
  private YamlConfiguration config;
  private StaffModeration gateway;
  private MockedStatic<Bukkit> bukkit;
  private MockedConstruction<StaffHierarchy> hierarchy;
  private final Set<String> permissions = new HashSet<>();
  private CompletableFuture<Boolean> hierarchyResult;
  private final ArrayDeque<Runnable> mainQueue = new ArrayDeque<>();
  private boolean primaryThread = true;
  private final List<Action> writtenActions = new ArrayList<>();

  @BeforeEach
  void setUp() {
    plugin = mock(MineplayPunishments.class);
    actor = mock(Player.class);
    store = mock(PunishmentService.class);
    community = mock(CommunityServiceManager.class);
    enforcement = mock(EnforcementListener.class);
    sync = mock(SyncClient.class);
    Messages messages = mock(Messages.class);
    config = new YamlConfiguration();
    for (String type : List.of("bans", "mutes", "warnings", "kicks"))
      config.set("punishments." + type + ".enabled", true);
    config.set("punishments.kicks.remote-command-expiry-seconds", 30);
    config.set("punishments.warnings.expiration-days", 0);
    config.set("community-service.enabled", true);
    config.set("community-service.equipment.default-pickaxe", "WOODEN_PICKAXE");
    config.set(
        "community-service.equipment.allowed-pickaxes", List.of("WOODEN_PICKAXE", "STONE_PICKAXE"));
    when(community.ready()).thenReturn(true);
    when(plugin.punishments()).thenReturn(store);
    when(plugin.community()).thenReturn(community);
    when(plugin.enforcement()).thenReturn(enforcement);
    when(plugin.sync()).thenReturn(sync);
    when(plugin.messages()).thenReturn(messages);
    when(plugin.getConfig()).thenReturn(config);
    when(plugin.settings()).thenReturn(settings(100));
    when(messages.text(anyString(), any(String[].class)))
        .thenReturn(Component.text("Moderation message"));
    when(actor.getUniqueId()).thenReturn(actorId);
    when(actor.isOnline()).thenReturn(true);
    when(actor.hasPermission(anyString()))
        .thenAnswer(
            call -> {
              assertTrue(primaryThread, "Player permission access must be on the main thread");
              return permissions.contains(call.getArgument(0));
            });
    when(store.healthy()).thenReturn(true);
    when(store.resolve(anyString()))
        .thenReturn(CompletableFuture.completedFuture(Optional.of(targetId)));
    when(store.apply(any(Action.class)))
        .thenAnswer(
            call -> {
              Action action = call.getArgument(0);
              writtenActions.add(action);
              return CompletableFuture.completedFuture(applied(action));
            });
    doAnswer(
            call -> {
              Runnable work = call.getArgument(0);
              if (primaryThread) work.run();
              else mainQueue.add(work);
              return null;
            })
        .when(plugin)
        .onMain(any(Runnable.class));
    bukkit = mockStatic(Bukkit.class);
    bukkit.when(Bukkit::isPrimaryThread).thenAnswer(ignored -> primaryThread);
    bukkit.when(() -> Bukkit.getPlayer(actorId)).thenReturn(actor);
    hierarchyResult = CompletableFuture.completedFuture(true);
    hierarchy =
        mockConstruction(
            StaffHierarchy.class,
            (mock, context) ->
                when(mock.mayPunish(any(), any())).thenAnswer(ignored -> hierarchyResult));
    permissions.add("mineplaypunishments.gui");
    permissions.add("mineplaypunishments.ban");
    gateway = new StaffModeration(plugin, Clock.fixed(now, ZoneOffset.UTC));
  }

  @AfterEach
  void tearDown() {
    hierarchy.close();
    bukkit.close();
  }

  @Test
  void resolvesVerifiedNameAndRejectsUnknownNameWithoutGuessingUuid() {
    assertEquals(targetId, gateway.resolve(actor, "Steve").join().target().id());
    when(store.resolve("Unknown")).thenReturn(CompletableFuture.completedFuture(Optional.empty()));
    StaffModeration.Resolution failure = gateway.resolve(actor, "Unknown").join();
    assertFalse(failure.resolved());
    assertEquals("UNKNOWN_TARGET", failure.code());
    verify(store, never()).apply(any());
  }

  @Test
  void onlineResolutionUsesCurrentVerifiedIdentity() {
    Player target = mock(Player.class);
    when(target.getUniqueId()).thenReturn(targetId);
    when(target.getName()).thenReturn("Steve");
    bukkit.when(() -> Bukkit.getPlayerExact("Steve")).thenReturn(target);
    StaffModeration.Resolution resolved = gateway.resolve(actor, "Steve").join();
    assertEquals(new StaffModeration.Target(targetId, "Steve"), resolved.target());
    verify(store, never()).resolve(any());
  }

  @Test
  void permanentBanUsesExistingPermissionAndDurableActionPath() {
    UUID confirmation = UUID.randomUUID();
    Result result =
        gateway.submit(actor, targetId, confirmation, Type.BAN, null, "  Griefing spawn  ").join();
    assertTrue(result.applied());
    Action action = writtenActions.getFirst();
    assertEquals(confirmation, action.id());
    assertEquals(actorId.toString(), action.issuer());
    assertEquals(Source.IN_GAME, action.source());
    assertEquals(ActionType.CREATE_BAN, action.type());
    assertEquals("Griefing spawn", action.payload().get("reason").getAsString());
    assertFalse(action.payload().has("expiresAt"));
    assertEquals(now, action.issuedAt());
    verify(enforcement).enforceAll();
    verify(sync).trigger();
  }

  @Test
  void temporaryBanNeedsTempbanPermissionAndCarriesExactExpiry() {
    Result denied =
        gateway
            .submit(actor, targetId, UUID.randomUUID(), Type.BAN, Duration.ofHours(2), "Test")
            .join();
    assertEquals("PERMISSION_DENIED", denied.code());
    assertTrue(writtenActions.isEmpty());
    permissions.add("mineplaypunishments.tempban");
    assertTrue(
        gateway
            .submit(actor, targetId, UUID.randomUUID(), Type.BAN, Duration.ofHours(2), "Test")
            .join()
            .applied());
    assertEquals(
        now.plusSeconds(7200).toString(),
        writtenActions.getFirst().payload().get("expiresAt").getAsString());
  }

  @Test
  void muteWarningAndKickUseTheirOwnPermissionsAndConfiguration() {
    permissions.addAll(
        Set.of(
            "mineplaypunishments.mute",
            "mineplaypunishments.tempmute",
            "mineplaypunishments.warn",
            "mineplaypunishments.kick"));
    gateway.submit(actor, targetId, UUID.randomUUID(), Type.MUTE, null, "Mute").join();
    gateway
        .submit(actor, targetId, UUID.randomUUID(), Type.MUTE, Duration.ofMinutes(30), "Mute")
        .join();
    config.set("punishments.warnings.expiration-days", 7);
    gateway.submit(actor, targetId, UUID.randomUUID(), Type.WARNING, null, "Warning").join();
    gateway.submit(actor, targetId, UUID.randomUUID(), Type.KICK, null, "Kick").join();
    assertEquals(
        List.of(
            ActionType.CREATE_MUTE,
            ActionType.CREATE_MUTE,
            ActionType.CREATE_WARNING,
            ActionType.CREATE_KICK),
        writtenActions.stream().map(Action::type).toList());
    assertEquals(
        now.plus(Duration.ofDays(7)).toString(),
        writtenActions.get(2).payload().get("expiresAt").getAsString());
    assertEquals(now.plusSeconds(30), writtenActions.get(3).validUntil());
  }

  @Test
  void guiPermissionCustodyHealthAndConfigurationGateRenderingAndSubmission() {
    assertTrue(gateway.available(actor, Type.BAN, null));
    permissions.remove("mineplaypunishments.gui");
    assertFalse(gateway.available(actor, Type.BAN, null));
    assertEquals(
        "PERMISSION_DENIED",
        gateway.submit(actor, targetId, UUID.randomUUID(), Type.BAN, null, "Test").join().code());
    permissions.add("mineplaypunishments.gui");
    when(community.restricted(actorId)).thenReturn(true);
    assertFalse(gateway.available(actor, Type.BAN, null));
    assertEquals("STAFF_RESTRICTED", gateway.resolve(actor, "Steve").join().code());
    when(community.restricted(actorId)).thenReturn(false);
    when(store.healthy()).thenReturn(false);
    assertFalse(gateway.available(actor, Type.BAN, null));
    when(store.healthy()).thenReturn(true);
    config.set("punishments.bans.enabled", false);
    assertFalse(gateway.available(actor, Type.BAN, null));
    assertEquals(
        "TYPE_DISABLED",
        gateway.submit(actor, targetId, UUID.randomUUID(), Type.BAN, null, "Test").join().code());
    assertTrue(writtenActions.isEmpty());
  }

  @Test
  void permissionRevokedWhileRankLoadsStopsCommit() {
    hierarchyResult = new CompletableFuture<>();
    CompletableFuture<Result> pending =
        gateway.submit(actor, targetId, UUID.randomUUID(), Type.BAN, null, "Test");
    permissions.remove("mineplaypunishments.ban");
    hierarchyResult.complete(true);
    assertEquals("PERMISSION_DENIED", pending.join().code());
    verify(store, never()).apply(any());
  }

  @Test
  void closingMenuDuringRankLookupCancelsBeforeCommit() {
    hierarchyResult = new CompletableFuture<>();
    AtomicBoolean session = new AtomicBoolean(true);
    CompletableFuture<Result> pending =
        gateway.submit(actor, targetId, UUID.randomUUID(), Type.BAN, null, "Test", session::get);
    session.set(false);
    hierarchyResult.complete(true);
    assertEquals("CANCELLED", pending.join().code());
    verify(store, never()).apply(any());
  }

  @Test
  void latestHierarchyDenialAndConfigReloadPreventCommit() {
    hierarchyResult = CompletableFuture.completedFuture(false);
    assertEquals(
        "TARGET_PROTECTED",
        gateway.submit(actor, targetId, UUID.randomUUID(), Type.BAN, null, "Test").join().code());
    hierarchyResult = new CompletableFuture<>();
    CompletableFuture<Result> pending =
        gateway.submit(actor, targetId, UUID.randomUUID(), Type.BAN, null, "Test");
    when(plugin.settings()).thenReturn(settings(80));
    hierarchyResult.complete(true);
    assertEquals("CONFIGURATION_CHANGED", pending.join().code());
    verify(store, never()).apply(any());
  }

  @Test
  void currentPlayerSessionMustRemainConnectedDuringAuthorization() {
    hierarchyResult = new CompletableFuture<>();
    CompletableFuture<Result> pending =
        gateway.submit(actor, targetId, UUID.randomUUID(), Type.BAN, null, "Test");
    bukkit.when(() -> Bukkit.getPlayer(actorId)).thenReturn(mock(Player.class));
    hierarchyResult.complete(true);
    assertEquals("PLAYER_OFFLINE", pending.join().code());
    verify(store, never()).apply(any());
  }

  @Test
  void repeatedConfirmationSharesFutureAndDoesNotCreateAnotherAction() {
    hierarchyResult = new CompletableFuture<>();
    UUID confirmation = UUID.randomUUID();
    CompletableFuture<Result> first =
        gateway.submit(actor, targetId, confirmation, Type.BAN, null, "Test");
    assertSame(first, gateway.submit(actor, targetId, confirmation, Type.BAN, null, "Test"));
    hierarchyResult.complete(true);
    assertTrue(first.join().applied());
    assertSame(first, gateway.submit(actor, targetId, confirmation, Type.BAN, null, "Test"));
    assertEquals(1, writtenActions.size());
    assertEquals(
        "REPLAY_MISMATCH",
        gateway.submit(actor, targetId, confirmation, Type.BAN, null, "Different").join().code());
    assertEquals(1, writtenActions.size());
  }

  @Test
  void storageRetryReusesExactActionIncludingTimestampsAndPunishmentId() {
    UUID confirmation = UUID.randomUUID();
    CompletableFuture<Result> databaseFailure = new CompletableFuture<>();
    when(store.apply(any(Action.class)))
        .thenAnswer(
            call -> {
              writtenActions.add(call.getArgument(0));
              return writtenActions.size() == 1
                  ? databaseFailure
                  : CompletableFuture.completedFuture(applied(call.getArgument(0)));
            });
    CompletableFuture<Result> first =
        gateway.submit(actor, targetId, confirmation, Type.BAN, null, "Test");
    databaseFailure.completeExceptionally(new IllegalStateException("database failed"));
    assertEquals("STORAGE_UNAVAILABLE", first.join().code());
    assertTrue(
        gateway.submit(actor, targetId, confirmation, Type.BAN, null, "Test").join().applied());
    assertSame(writtenActions.get(0), writtenActions.get(1));
  }

  @Test
  void completedCacheIsBoundedButActiveRequestsAreNeverEvicted() {
    hierarchyResult = new CompletableFuture<>();
    for (int i = 0; i < 256; i++)
      gateway.submit(actor, targetId, UUID.randomUUID(), Type.BAN, null, "Test");
    assertEquals(
        "BUSY",
        gateway.submit(actor, targetId, UUID.randomUUID(), Type.BAN, null, "Test").join().code());
    hierarchyResult.complete(false);
    hierarchyResult = CompletableFuture.completedFuture(true);
    assertTrue(
        gateway
            .submit(actor, targetId, UUID.randomUUID(), Type.BAN, null, "Test")
            .join()
            .applied());
  }

  @Test
  void asyncResolutionAndRankCompletionTouchBukkitOnlyAfterMainDispatch() {
    CompletableFuture<Optional<UUID>> lookup = new CompletableFuture<>();
    when(store.resolve("Steve")).thenReturn(lookup);
    CompletableFuture<StaffModeration.Resolution> result = gateway.resolve(actor, "Steve");
    primaryThread = false;
    lookup.complete(Optional.of(targetId));
    assertFalse(result.isDone());
    primaryThread = true;
    drainMain();
    assertTrue(result.join().resolved());
    hierarchyResult = new CompletableFuture<>();
    CompletableFuture<Result> submission =
        gateway.submit(actor, targetId, UUID.randomUUID(), Type.BAN, null, "Test");
    primaryThread = false;
    hierarchyResult.complete(true);
    assertFalse(submission.isDone());
    primaryThread = true;
    drainMain();
    assertTrue(submission.join().applied());
  }

  @Test
  void reasonAndDurationValidationRejectsUnsafeOrUnsupportedInput() {
    assertEquals(
        "INVALID_REASON",
        gateway.submit(actor, targetId, UUID.randomUUID(), Type.BAN, null, "\n").join().code());
    assertEquals(
        "INVALID_REASON",
        gateway
            .submit(actor, targetId, UUID.randomUUID(), Type.BAN, null, "x".repeat(101))
            .join()
            .code());
    assertEquals(
        "INVALID_DURATION",
        gateway
            .submit(actor, targetId, UUID.randomUUID(), Type.BAN, Duration.ZERO, "Test")
            .join()
            .code());
    assertEquals(
        "INVALID_DURATION",
        gateway
            .submit(actor, targetId, UUID.randomUUID(), Type.KICK, Duration.ofHours(1), "Test")
            .join()
            .code());
    assertEquals(
        "UNSUPPORTED_TYPE",
        gateway
            .submit(actor, targetId, UUID.randomUUID(), Type.COMMUNITY_SERVICE, null, "Test")
            .join()
            .code());
    verify(store, never()).apply(any());
  }

  @Test
  void serviceOnlyStaffCanResolveAndIssueUsingConfiguredToolAndExactBlockCount() {
    permissions.clear();
    permissions.addAll(Set.of("mineplaypunishments.gui", "mineplaypunishments.service.issue"));
    assertTrue(gateway.availableService(actor));
    assertFalse(gateway.available(actor, Type.BAN, null));
    assertTrue(gateway.resolve(actor, "Steve").join().resolved());
    config.set("community-service.equipment.default-pickaxe", "STONE_PICKAXE");
    UUID confirmation = UUID.randomUUID();
    Result result =
        gateway
            .submitService(actor, targetId, confirmation, 275, "  Griefing spawn  ", () -> true)
            .join();
    assertTrue(result.applied());
    Action action = writtenActions.getFirst();
    assertEquals(confirmation, action.id());
    assertEquals(ActionType.CREATE_COMMUNITY_SERVICE, action.type());
    assertEquals(275, action.payload().get("blocks").getAsInt());
    assertEquals("STONE_PICKAXE", action.payload().get("tool").getAsString());
    assertEquals("Griefing spawn", action.payload().get("reason").getAsString());
    assertEquals(Set.of("blocks", "tool", "reason"), action.payload().keySet());
    assertEquals(targetId, action.targetId());
    assertEquals(Source.IN_GAME, action.source());
    assertNull(action.validUntil());
    verify(enforcement).enforceAll();
    verify(sync).trigger();
  }

  @Test
  void serviceBlockBoundsAreInclusiveAndOrdinarySubmitCannotOmitTheBlockCount() {
    permissions.add("mineplaypunishments.service.issue");
    for (int invalid : new int[] {-1, 0, 9, 10001, Integer.MAX_VALUE}) {
      assertEquals(
          "INVALID_BLOCKS",
          gateway
              .submitService(actor, targetId, UUID.randomUUID(), invalid, "Test", () -> true)
              .join()
              .code());
    }
    assertTrue(writtenActions.isEmpty());
    for (int valid : new int[] {10, 10000}) {
      assertTrue(
          gateway
              .submitService(actor, targetId, UUID.randomUUID(), valid, "Test", () -> true)
              .join()
              .applied());
    }
    assertEquals(
        List.of(10, 10000),
        writtenActions.stream().map(a -> a.payload().get("blocks").getAsInt()).toList());
    assertEquals(
        "UNSUPPORTED_TYPE",
        gateway
            .submit(actor, targetId, UUID.randomUUID(), Type.COMMUNITY_SERVICE, null, "Test")
            .join()
            .code());
    assertEquals(
        "UNSUPPORTED_TYPE",
        gateway
            .submit(
                actor,
                targetId,
                UUID.randomUUID(),
                Type.COMMUNITY_SERVICE,
                Duration.ofMinutes(1),
                "Test")
            .join()
            .code());
  }

  @Test
  void servicePermissionEnabledFlagAndQuarryReadinessGateSubmissionAndRendering() {
    assertFalse(gateway.availableService(actor));
    assertEquals(
        "PERMISSION_DENIED",
        gateway
            .submitService(actor, targetId, UUID.randomUUID(), 250, "Test", () -> true)
            .join()
            .code());
    permissions.add("mineplaypunishments.service.issue");
    when(community.ready()).thenReturn(false);
    assertFalse(gateway.availableService(actor));
    assertEquals(
        "QUARRY_UNAVAILABLE",
        gateway
            .submitService(actor, targetId, UUID.randomUUID(), 250, "Test", () -> true)
            .join()
            .code());
    when(community.ready()).thenReturn(true);
    config.set("community-service.enabled", false);
    assertFalse(gateway.availableService(actor));
    assertEquals(
        "TYPE_DISABLED",
        gateway
            .submitService(actor, targetId, UUID.randomUUID(), 250, "Test", () -> true)
            .join()
            .code());
    verify(store, never()).apply(any());
  }

  @Test
  void serviceRejectsUnsupportedMissingOrDisallowedConfiguredTool() {
    permissions.add("mineplaypunishments.service.issue");
    for (String invalid : new String[] {"DIAMOND_PICKAXE", "wooden", "", null}) {
      config.set("community-service.equipment.default-pickaxe", invalid);
      assertFalse(gateway.availableService(actor));
      assertEquals(
          "INVALID_TOOL",
          gateway
              .submitService(actor, targetId, UUID.randomUUID(), 250, "Test", () -> true)
              .join()
              .code());
    }
    config.set("community-service.equipment.default-pickaxe", "STONE_PICKAXE");
    config.set("community-service.equipment.allowed-pickaxes", List.of("WOODEN_PICKAXE"));
    assertEquals(
        "INVALID_TOOL",
        gateway
            .submitService(actor, targetId, UUID.randomUUID(), 250, "Test", () -> true)
            .join()
            .code());
    verify(store, never()).apply(any());
  }

  @Test
  void quarryBecomingUnavailableWhileRankLoadsPreventsServiceCommit() {
    permissions.add("mineplaypunishments.service.issue");
    hierarchyResult = new CompletableFuture<>();
    CompletableFuture<Result> pending =
        gateway.submitService(actor, targetId, UUID.randomUUID(), 250, "Test", () -> true);
    when(community.ready()).thenReturn(false);
    hierarchyResult.complete(true);
    assertEquals("QUARRY_UNAVAILABLE", pending.join().code());
    verify(store, never()).apply(any());
  }

  @Test
  void changedServiceDefaultToolRequiresFreshConfirmationEvenWhenBothToolsAreAllowed() {
    permissions.add("mineplaypunishments.service.issue");
    hierarchyResult = new CompletableFuture<>();
    CompletableFuture<Result> pending =
        gateway.submitService(actor, targetId, UUID.randomUUID(), 250, "Test", () -> true);
    config.set("community-service.equipment.default-pickaxe", "STONE_PICKAXE");
    hierarchyResult.complete(true);
    assertEquals("CONFIGURATION_CHANGED", pending.join().code());
    verify(store, never()).apply(any());
  }

  @Test
  void removedServiceToolOrTighterBlockLimitsAreRecheckedAfterRankLookup() {
    permissions.add("mineplaypunishments.service.issue");
    hierarchyResult = new CompletableFuture<>();
    CompletableFuture<Result> removedTool =
        gateway.submitService(actor, targetId, UUID.randomUUID(), 250, "Test", () -> true);
    config.set("community-service.equipment.allowed-pickaxes", List.of("STONE_PICKAXE"));
    hierarchyResult.complete(true);
    assertEquals("INVALID_TOOL", removedTool.join().code());
    config.set(
        "community-service.equipment.allowed-pickaxes", List.of("WOODEN_PICKAXE", "STONE_PICKAXE"));
    hierarchyResult = new CompletableFuture<>();
    CompletableFuture<Result> changedRange =
        gateway.submitService(actor, targetId, UUID.randomUUID(), 250, "Test", () -> true);
    when(plugin.settings())
        .thenReturn(
            new PluginSettings("test", Path.of("test.db"), 10, 200, 100, List.of(10, 100), 10000));
    hierarchyResult.complete(true);
    assertEquals("INVALID_BLOCKS", changedRange.join().code());
    verify(store, never()).apply(any());
  }

  @Test
  void servicePermissionRevocationAndLatestHierarchyDenialPreventCommit() {
    permissions.add("mineplaypunishments.service.issue");
    hierarchyResult = new CompletableFuture<>();
    CompletableFuture<Result> revoked =
        gateway.submitService(actor, targetId, UUID.randomUUID(), 250, "Test", () -> true);
    permissions.remove("mineplaypunishments.service.issue");
    hierarchyResult.complete(true);
    assertEquals("PERMISSION_DENIED", revoked.join().code());
    permissions.add("mineplaypunishments.service.issue");
    hierarchyResult = new CompletableFuture<>();
    CompletableFuture<Result> protectedTarget =
        gateway.submitService(actor, targetId, UUID.randomUUID(), 250, "Test", () -> true);
    hierarchyResult.complete(false);
    assertEquals("TARGET_PROTECTED", protectedTarget.join().code());
    verify(store, never()).apply(any());
  }

  @Test
  void serviceSessionCancellationAndStaffCustodyAreRecheckedAfterAuthorization() {
    permissions.add("mineplaypunishments.service.issue");
    hierarchyResult = new CompletableFuture<>();
    AtomicBoolean session = new AtomicBoolean(true);
    CompletableFuture<Result> cancelled =
        gateway.submitService(actor, targetId, UUID.randomUUID(), 250, "Test", session::get);
    session.set(false);
    hierarchyResult.complete(true);
    assertEquals("CANCELLED", cancelled.join().code());
    hierarchyResult = new CompletableFuture<>();
    CompletableFuture<Result> restricted =
        gateway.submitService(actor, targetId, UUID.randomUUID(), 250, "Test", () -> true);
    when(community.restricted(actorId)).thenReturn(true);
    hierarchyResult.complete(true);
    assertEquals("STAFF_RESTRICTED", restricted.join().code());
    verify(store, never()).apply(any());
  }

  @Test
  void duplicateServiceConfirmationCannotDoubleSentenceOrChangeItsBlocks() {
    permissions.add("mineplaypunishments.service.issue");
    hierarchyResult = new CompletableFuture<>();
    UUID confirmation = UUID.randomUUID();
    CompletableFuture<Result> pending =
        gateway.submitService(actor, targetId, confirmation, 250, "Test", () -> true);
    assertSame(
        pending, gateway.submitService(actor, targetId, confirmation, 250, "Test", () -> true));
    assertEquals(
        "REPLAY_MISMATCH",
        gateway
            .submitService(actor, targetId, confirmation, 500, "Test", () -> true)
            .join()
            .code());
    hierarchyResult.complete(true);
    assertTrue(pending.join().applied());
    assertSame(
        pending, gateway.submitService(actor, targetId, confirmation, 250, "Test", () -> true));
    assertEquals(1, writtenActions.size());
    assertEquals(250, writtenActions.getFirst().payload().get("blocks").getAsInt());
  }

  @Test
  void serviceAuthorizationCompletionReschedulesAllChecksOnMainThread() {
    permissions.add("mineplaypunishments.service.issue");
    hierarchyResult = new CompletableFuture<>();
    CompletableFuture<Result> pending =
        gateway.submitService(actor, targetId, UUID.randomUUID(), 250, "Test", () -> true);
    primaryThread = false;
    hierarchyResult.complete(true);
    assertFalse(pending.isDone());
    assertTrue(writtenActions.isEmpty());
    primaryThread = true;
    drainMain();
    assertTrue(pending.join().applied());
  }

  @Test
  void postCommitEnforcementFailureStillCompletesAndDoesNotLeaveSubmissionInFlight() {
    doThrow(new IllegalStateException("enforcement failure")).when(enforcement).enforceAll();
    UUID confirmation = UUID.randomUUID();
    CompletableFuture<Result> completed =
        gateway.submit(actor, targetId, confirmation, Type.BAN, null, "Test");
    assertTrue(completed.isDone());
    assertTrue(completed.join().applied());
    assertSame(completed, gateway.submit(actor, targetId, confirmation, Type.BAN, null, "Test"));
    assertEquals(1, writtenActions.size());
    verify(plugin).logFailure(eq("gui_post_commit_enforcement"), any(IllegalStateException.class));
  }

  private void drainMain() {
    while (!mainQueue.isEmpty()) mainQueue.remove().run();
  }

  private PluginSettings settings(int reasonLimit) {
    return new PluginSettings(
        "test", Path.of("test.db"), 10, 10000, reasonLimit, List.of(10, 100), 10000);
  }

  private Result applied(Action action) {
    Type type = Type.valueOf(action.type().name().substring("CREATE_".length()));
    Punishment punishment =
        new Punishment(
            action.punishmentId(),
            "MP-TEST",
            action.targetId(),
            "Steve",
            type,
            action.payload().get("reason").getAsString(),
            action.issuer(),
            action.source(),
            action.issuedAt(),
            null,
            type == Type.KICK ? Status.COMPLETED : Status.ACTIVE,
            1,
            null);
    return new Result(true, "APPLIED", "Saved", punishment);
  }
}
