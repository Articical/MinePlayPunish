package cloud.mineplay.punishments.enforcement;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cloud.mineplay.punishments.MineplayPunishments;
import cloud.mineplay.punishments.communityservice.CommunityServiceManager;
import cloud.mineplay.punishments.config.Messages;
import cloud.mineplay.punishments.punishment.Models.*;
import cloud.mineplay.punishments.punishment.PunishmentService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class EnforcementScopeTest {
  private final Component unavailable = Component.text("Storage unavailable");
  private final Component banMessage = Component.text("Banned");
  private final Component kickMessage = Component.text("Kicked");
  private final List<Player> players = new ArrayList<>();
  private final List<Runnable> delayed = new ArrayList<>();
  private MockedStatic<Bukkit> bukkit;
  private MineplayPunishments plugin;
  private PunishmentService service;
  private CommunityServiceManager community;
  private EnforcementListener listener;

  @BeforeEach
  void setup() {
    plugin = mock(MineplayPunishments.class);
    service = mock(PunishmentService.class);
    community = mock(CommunityServiceManager.class);
    Messages messages = mock(Messages.class);
    when(messages.text("storage-unavailable")).thenReturn(unavailable);
    when(messages.punishment(eq("ban"), any())).thenReturn(banMessage);
    when(messages.punishment(eq("kick"), any())).thenReturn(kickMessage);
    when(plugin.messages()).thenReturn(messages);
    when(plugin.getConfig()).thenReturn(new YamlConfiguration());
    when(plugin.punishments()).thenReturn(service);
    when(plugin.community()).thenReturn(community);
    doAnswer(
            invocation -> {
              invocation.<Runnable>getArgument(0).run();
              return null;
            })
        .when(plugin)
        .onMain(any(Runnable.class));
    when(service.healthy()).thenReturn(true);
    when(service.active(any(), any())).thenReturn(Optional.empty());
    when(service.pendingKicks()).thenReturn(List.of());
    when(service.pendingWarnings(any())).thenReturn(CompletableFuture.completedFuture(List.of()));
    when(service.rememberPlayer(any(), anyString()))
        .thenReturn(CompletableFuture.completedFuture(null));
    when(service.claimKick(any())).thenReturn(CompletableFuture.completedFuture(true));
    when(service.markKick(any(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
    BukkitScheduler scheduler = mock(BukkitScheduler.class);
    when(scheduler.runTaskLater(eq(plugin), any(Runnable.class), eq(1L)))
        .thenAnswer(
            invocation -> {
              delayed.add(invocation.getArgument(1));
              return mock(BukkitTask.class);
            });
    bukkit = mockStatic(Bukkit.class);
    bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
    for (int i = 0; i < 4; i++) {
      Player player = mock(Player.class);
      UUID id = UUID.randomUUID();
      when(player.getUniqueId()).thenReturn(id);
      when(player.getName()).thenReturn("Player" + i);
      when(player.isOnline()).thenReturn(true);
      bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(player);
      players.add(player);
    }
    bukkit.when(Bukkit::getOnlinePlayers).thenReturn(players);
    listener = new EnforcementListener(plugin);
  }

  @AfterEach
  void cleanup() {
    bukkit.close();
  }

  @Test
  void targetedBanDoesNotEnforceOtherPlayersExistingBansOrPendingKicks() {
    Player target = players.getFirst();
    Punishment targetBan = punishment(target, Type.BAN);
    Punishment otherBan = punishment(players.get(1), Type.BAN);
    when(service.active(target.getUniqueId(), Type.BAN)).thenReturn(Optional.of(targetBan));
    when(service.active(players.get(1).getUniqueId(), Type.BAN)).thenReturn(Optional.of(otherBan));
    Punishment otherKick = punishment(players.get(2), Type.KICK);
    when(service.pendingKicks()).thenReturn(List.of(otherKick));

    listener.enforce(target.getUniqueId());

    verify(target).kick(banMessage);
    verifyNoInteractions(community);
    verify(service, never()).claimKick(any());
    verify(service, never()).kickDeadline(otherKick.id());
    verifyUntouchedOtherPlayers();
    bukkit.verify(Bukkit::getOnlinePlayers, never());
  }

  @Test
  void targetedServiceReconcilesOnlyItsPlayer() {
    Player target = players.getFirst();

    listener.enforce(target.getUniqueId());

    verify(community).reconcile(target);
    verify(service).pendingWarnings(target.getUniqueId());
    verify(target, never()).kick(any(Component.class));
    verifyUntouchedOtherPlayers();
    bukkit.verify(Bukkit::getOnlinePlayers, never());
  }

  @Test
  void targetedKickDoesNotClaimOrExpireOtherPlayersKicks() {
    Player target = players.getFirst();
    Punishment selected = punishment(target, Type.KICK);
    Punishment other = punishment(players.get(1), Type.KICK);
    when(service.pendingKicks()).thenReturn(List.of(other, selected));
    when(service.kickDeadline(selected.id()))
        .thenReturn(Optional.of(Instant.now().plusSeconds(60)));

    listener.enforce(target.getUniqueId());

    verify(target).kick(kickMessage);
    verify(service).claimKick(selected.id());
    verify(service, never()).kickDeadline(other.id());
    verify(service, never()).claimKick(other.id());
    verify(service, never()).markKick(eq(other.id()), anyString());
    verifyUntouchedOtherPlayers();
  }

  @Test
  void offlineTargetMarksOnlyItsOwnPendingKickFailed() {
    Player target = players.getFirst();
    Punishment selected = punishment(target, Type.KICK);
    Punishment other = punishment(players.get(1), Type.KICK);
    when(service.pendingKicks()).thenReturn(List.of(selected, other));
    when(service.kickDeadline(selected.id()))
        .thenReturn(Optional.of(Instant.now().plusSeconds(60)));
    bukkit.when(() -> Bukkit.getPlayer(target.getUniqueId())).thenReturn(null);

    listener.enforce(target.getUniqueId());

    verify(service).markKick(selected.id(), "FAILED");
    verify(service, never()).claimKick(any());
    verify(service, never()).markKick(eq(other.id()), anyString());
    verifyNoInteractions(community);
    verifyNoDisconnections();
    verifyUntouchedOtherPlayers();
  }

  @Test
  void expiredTargetKickNeverDisconnectsAnyone() {
    Player target = players.getFirst();
    Punishment selected = punishment(target, Type.KICK);
    when(service.pendingKicks()).thenReturn(List.of(selected));
    when(service.kickDeadline(selected.id()))
        .thenReturn(Optional.of(Instant.now().minusSeconds(1)));

    listener.enforce(target.getUniqueId());

    verify(service).markKick(selected.id(), "EXPIRED");
    verify(service, never()).claimKick(any());
    verifyNoDisconnections();
  }

  @Test
  void repeatedTargetedPassesCannotDeliverAnInFlightKickTwice() {
    Player target = players.getFirst();
    Punishment selected = punishment(target, Type.KICK);
    CompletableFuture<Boolean> claim = new CompletableFuture<>();
    when(service.pendingKicks()).thenReturn(List.of(selected));
    when(service.kickDeadline(selected.id()))
        .thenReturn(Optional.of(Instant.now().plusSeconds(60)));
    when(service.claimKick(selected.id())).thenReturn(claim);

    listener.enforce(target.getUniqueId());
    listener.enforce(target.getUniqueId());
    verifyNoDisconnections();
    claim.complete(true);
    listener.enforce(target.getUniqueId());

    verify(service, times(1)).claimKick(selected.id());
    verify(target, times(1)).kick(kickMessage);
    assertEquals(1, delayed.size());
    when(target.isOnline()).thenReturn(false);
    delayed.getFirst().run();
    verify(service).markKick(selected.id(), "EXECUTED");
    verifyUntouchedOtherPlayers();
  }

  @Test
  void lostSessionDuringClaimCannotDisconnectAPlayer() {
    Player target = players.getFirst();
    Punishment selected = punishment(target, Type.KICK);
    CompletableFuture<Boolean> claim = new CompletableFuture<>();
    when(service.pendingKicks()).thenReturn(List.of(selected));
    when(service.kickDeadline(selected.id()))
        .thenReturn(Optional.of(Instant.now().plusSeconds(60)));
    when(service.claimKick(selected.id())).thenReturn(claim);

    listener.enforce(target.getUniqueId());
    when(target.isOnline()).thenReturn(false);
    claim.complete(true);

    verifyNoDisconnections();
    verify(service).markKick(selected.id(), "FAILED");
    assertTrue(delayed.isEmpty());
  }

  @Test
  void storageFailureWhileClaimingKickCannotDisconnectAnyone() {
    Player target = players.getFirst();
    Punishment selected = punishment(target, Type.KICK);
    when(service.pendingKicks()).thenReturn(List.of(selected));
    when(service.kickDeadline(selected.id()))
        .thenReturn(Optional.of(Instant.now().plusSeconds(60)));
    when(service.claimKick(selected.id()))
        .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("Store unavailable")));

    listener.enforce(target.getUniqueId());

    verifyNoDisconnections();
    verify(service, never()).markKick(any(), anyString());
    assertTrue(delayed.isEmpty());
    verifyUntouchedOtherPlayers();
  }

  @Test
  void sameUuidReconnectCannotReceiveAnOldSessionsClaimedKick() {
    Player original = players.getFirst();
    UUID target = original.getUniqueId();
    Player replacement = mock(Player.class);
    when(replacement.getUniqueId()).thenReturn(target);
    when(replacement.isOnline()).thenReturn(true);
    Punishment selected = punishment(original, Type.KICK);
    CompletableFuture<Boolean> claim = new CompletableFuture<>();
    when(service.pendingKicks()).thenReturn(List.of(selected));
    when(service.kickDeadline(selected.id()))
        .thenReturn(Optional.of(Instant.now().plusSeconds(60)));
    when(service.claimKick(selected.id())).thenReturn(claim);

    listener.enforce(target);
    // Some player wrappers may still report online by UUID after their connection is replaced.
    bukkit.when(() -> Bukkit.getPlayer(target)).thenReturn(replacement);
    claim.complete(true);

    verifyNoDisconnections();
    verify(replacement, never()).kick(any(Component.class));
    verify(service).markKick(selected.id(), "FAILED");
    assertTrue(delayed.isEmpty());
  }

  @Test
  void periodicPassStillEnforcesExistingBansAndPendingKicks() {
    Player banned = players.getFirst();
    Player kicked = players.get(1);
    Punishment existingBan = punishment(banned, Type.BAN);
    Punishment pending = punishment(kicked, Type.KICK);
    when(service.active(banned.getUniqueId(), Type.BAN)).thenReturn(Optional.of(existingBan));
    when(service.pendingKicks()).thenReturn(List.of(pending));
    when(service.kickDeadline(pending.id())).thenReturn(Optional.of(Instant.now().plusSeconds(60)));

    listener.enforceAll();

    verify(banned).kick(banMessage);
    verify(kicked).kick(kickMessage);
    verify(service).claimKick(pending.id());
    for (int i = 2; i < players.size(); i++) {
      verify(players.get(i), never()).kick(any(Component.class));
      verify(community).reconcile(players.get(i));
    }
  }

  @Test
  void unhealthyStoreDefersBothExistingSessionEntryPoints() {
    when(service.healthy()).thenReturn(false);

    listener.enforceAll();
    listener.enforce(players.getFirst().getUniqueId());

    verifyNoDisconnections();
    verifyNoInteractions(community);
    verify(service, never()).active(any(), any());
    verify(service, never()).pendingKicks();
    verify(service, never()).pendingWarnings(any());
    bukkit.verify(Bukkit::getOnlinePlayers, never());
  }

  @Test
  void communityWriteInvalidatingCacheDoesNotDisconnectFollowingPlayers() {
    AtomicBoolean healthy = new AtomicBoolean(true);
    when(service.healthy()).thenAnswer(ignored -> healthy.get());
    doAnswer(
            ignored -> {
              healthy.set(false);
              return null;
            })
        .when(community)
        .reconcile(players.getFirst());

    listener.enforceAll();

    verify(community).reconcile(players.getFirst());
    verifyNoDisconnections();
    verifyUntouchedOtherPlayers();
    verify(service, never()).pendingKicks();
  }

  @Test
  void invalidationInsideBanReadDefersEvenACachedBanUntilRefresh() {
    AtomicBoolean healthy = new AtomicBoolean(true);
    Player target = players.getFirst();
    Punishment oldBan = punishment(target, Type.BAN);
    when(service.healthy()).thenAnswer(ignored -> healthy.get());
    when(service.active(target.getUniqueId(), Type.BAN))
        .thenAnswer(
            ignored -> {
              healthy.set(false);
              return Optional.of(oldBan);
            });

    listener.enforce(target.getUniqueId());

    verifyNoDisconnections();
    verifyNoInteractions(community);
    verify(service, never()).pendingKicks();
  }

  @Test
  void refreshedCacheResumesDeferredPeriodicEnforcement() {
    Player target = players.getFirst();
    Punishment existingBan = punishment(target, Type.BAN);
    when(service.healthy()).thenReturn(false);
    when(service.active(target.getUniqueId(), Type.BAN)).thenReturn(Optional.of(existingBan));
    listener.enforceAll();
    verifyNoDisconnections();

    when(service.healthy()).thenReturn(true);
    listener.enforceAll();

    verify(target).kick(banMessage);
    for (int i = 1; i < players.size(); i++)
      verify(players.get(i), never()).kick(any(Component.class));
  }

  @Test
  void newlyJoiningPlayerStillFailsClosedOnUnverifiedStorage() {
    Player target = players.getFirst();
    when(service.healthy()).thenReturn(false);
    PlayerJoinEvent event = mock(PlayerJoinEvent.class);
    when(event.getPlayer()).thenReturn(target);

    listener.join(event);

    verify(target).kick(unavailable);
    verifyNoInteractions(community);
    verifyUntouchedOtherPlayers();
  }

  @Test
  void joiningPlayerStillFailsClosedIfCacheInvalidatesDuringBanLookup() {
    AtomicBoolean healthy = new AtomicBoolean(true);
    Player target = players.getFirst();
    when(service.healthy()).thenAnswer(ignored -> healthy.get());
    when(service.active(target.getUniqueId(), Type.BAN))
        .thenAnswer(
            ignored -> {
              healthy.set(false);
              return Optional.empty();
            });
    PlayerJoinEvent event = mock(PlayerJoinEvent.class);
    when(event.getPlayer()).thenReturn(target);

    listener.join(event);

    verify(target).kick(unavailable);
    verifyNoInteractions(community);
    verifyUntouchedOtherPlayers();
  }

  private Punishment punishment(Player player, Type type) {
    return new Punishment(
        UUID.randomUUID(),
        "MP-TEST",
        player.getUniqueId(),
        player.getName(),
        type,
        "Reason",
        "CONSOLE",
        Source.CONSOLE,
        Instant.now(),
        null,
        Status.ACTIVE,
        1,
        null);
  }

  private void verifyNoDisconnections() {
    for (Player player : players) verify(player, never()).kick(any(Component.class));
  }

  private void verifyUntouchedOtherPlayers() {
    for (int i = 1; i < players.size(); i++) {
      Player player = players.get(i);
      verify(player, never()).kick(any(Component.class));
      verify(community, never()).reconcile(player);
      verify(service, never()).active(player.getUniqueId(), Type.BAN);
      verify(service, never()).pendingWarnings(player.getUniqueId());
      bukkit.verify(() -> Bukkit.getPlayer(player.getUniqueId()), never());
    }
  }
}
