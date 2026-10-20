package cloud.mineplay.punishments.communityservice;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cloud.mineplay.punishments.punishment.Models.Sentence;
import cloud.mineplay.punishments.punishment.PunishmentService;
import cloud.mineplay.punishments.storage.Database;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/** Exercises actual queued release callbacks, rather than only repository state transitions. */
class ReleaseLifecycleTest {
  private CommunityServiceManager manager;
  private PunishmentService service;
  private ServiceRepository repository;
  private Player player;
  private Sentence sentence;
  private MockedStatic<Bukkit> bukkit;
  private final ArrayDeque<Runnable> mainQueue = new ArrayDeque<>();

  @BeforeEach
  void setup() throws Exception {
    JavaPlugin plugin = mock(JavaPlugin.class);
    when(plugin.namespace()).thenReturn("mineplaypunishments");
    when(plugin.isEnabled()).thenReturn(true);
    when(plugin.getLogger()).thenReturn(mock(Logger.class));
    service = mock(PunishmentService.class);
    when(service.healthy()).thenReturn(true);
    repository = mock(ServiceRepository.class);
    player = mock(Player.class);
    UUID id = UUID.randomUUID();
    when(player.getUniqueId()).thenReturn(id);
    when(player.isOnline()).thenReturn(true);
    sentence =
        new Sentence(
            UUID.randomUUID(), UUID.randomUUID(), id, 100, 100, "WOODEN_PICKAXE", "WORK_COMPLETE");
    when(service.sentence(id)).thenReturn(Optional.of(sentence));
    manager =
        new CommunityServiceManager(
            plugin, new Database(Path.of("unused-release-test.db")), service);
    field("repository", repository);
    field("running", true);
    BukkitScheduler scheduler = mock(BukkitScheduler.class);
    when(scheduler.runTask(eq(plugin), any(Runnable.class)))
        .thenAnswer(
            call -> {
              mainQueue.addLast(call.getArgument(1));
              return mock(BukkitTask.class);
            });
    bukkit = mockStatic(Bukkit.class);
    bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
    bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(player);
  }

  @AfterEach
  void cleanup() {
    if (bukkit != null) bukkit.close();
  }

  @Test
  void releaseBeforeAdmissionUnfreezesOnlyAfterCommitAndCacheRefresh() throws Exception {
    when(repository.snapshot(sentence.id()))
        .thenReturn(CompletableFuture.completedFuture(Optional.empty()));
    CompletableFuture<Void> committed = new CompletableFuture<>();
    CompletableFuture<Void> refreshed = new CompletableFuture<>();
    when(repository.complete(sentence, false)).thenReturn(committed);
    when(service.refresh()).thenReturn(refreshed);
    playerSet("prepared").add(player.getUniqueId());
    manager.reconcile(player);
    assertTrue(playerSet("processing").contains(player.getUniqueId()));
    assertTrue(manager.restricted(player.getUniqueId()));
    tick();
    verify(repository).complete(sentence, false);
    committed.complete(null);
    assertTrue(
        manager.restricted(player.getUniqueId()), "Release is still waiting for cache publication");
    when(service.sentence(player.getUniqueId())).thenReturn(Optional.empty());
    refreshed.complete(null);
    assertTrue(playerSet("processing").contains(player.getUniqueId()));
    tick();
    assertFalse(manager.restricted(player.getUniqueId()));
    assertFalse(playerSet("processing").contains(player.getUniqueId()));
    assertFalse(playerSet("prepared").contains(player.getUniqueId()));
    assertOrdinaryMovementAndDropsAllowed();
    verify(repository, never()).recovery(any());
  }

  @Test
  void disconnectDuringSnapshotLookupReleasesOnlyTheInMemoryTransition() throws Exception {
    CompletableFuture<Optional<ServiceRepository.Snapshot>> snapshot = new CompletableFuture<>();
    when(repository.snapshot(sentence.id())).thenReturn(snapshot);
    manager.reconcile(player);
    assertTrue(playerSet("processing").contains(player.getUniqueId()));
    when(player.isOnline()).thenReturn(false);
    snapshot.complete(Optional.empty());
    tick();
    assertFalse(playerSet("processing").contains(player.getUniqueId()));
    assertTrue(
        manager.restricted(player.getUniqueId()), "Durable unfinished sentence remains restricted");
    verify(repository, never()).complete(any(), anyBoolean());
  }

  @Test
  void failedSnapshotLookupEntersRecoveryAndClearsTheTransitionLatch() throws Exception {
    when(repository.snapshot(sentence.id()))
        .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("read unavailable")));
    when(repository.recovery(sentence)).thenReturn(CompletableFuture.completedFuture(null));
    when(service.refresh()).thenReturn(CompletableFuture.completedFuture(null));
    manager.reconcile(player);
    tick();
    tick();
    verify(repository).recovery(sentence);
    assertFalse(playerSet("processing").contains(player.getUniqueId()));
    assertTrue(manager.restricted(player.getUniqueId()));
  }

  @Test
  void recoveryWriteFailureDoesNotPermanentlyLatchProcessingAfterStorageRecovers()
      throws Exception {
    when(repository.snapshot(sentence.id()))
        .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("read unavailable")));
    when(repository.recovery(sentence))
        .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("write unavailable")));
    manager.reconcile(player);
    tick();
    tick();
    assertFalse(
        playerSet("processing").contains(player.getUniqueId()),
        "A failed completed future cannot remain an in-progress transition forever");
    assertTrue(
        manager.restricted(player.getUniqueId()),
        "Existing sentence still prevents unsafe release");
    when(repository.snapshot(sentence.id())).thenReturn(new CompletableFuture<>());
    manager.reconcile(player);
    verify(repository, times(2)).snapshot(sentence.id());
    assertTrue(
        playerSet("processing").contains(player.getUniqueId()), "A fresh attempt may now proceed");
  }

  @Test
  void recoveryCacheRefreshFailureAlsoClearsTheTransitionLatch() throws Exception {
    when(repository.snapshot(sentence.id()))
        .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("read unavailable")));
    when(repository.recovery(sentence)).thenReturn(CompletableFuture.completedFuture(null));
    when(service.refresh())
        .thenReturn(
            CompletableFuture.failedFuture(new IllegalStateException("refresh unavailable")));
    manager.reconcile(player);
    tick();
    tick();
    assertFalse(playerSet("processing").contains(player.getUniqueId()));
    assertTrue(manager.restricted(player.getUniqueId()));
  }

  private void assertOrdinaryMovementAndDropsAllowed() {
    World world = mock(World.class);
    when(world.getUID()).thenReturn(UUID.randomUUID());
    PlayerMoveEvent move = mock(PlayerMoveEvent.class);
    when(move.getPlayer()).thenReturn(player);
    when(move.getFrom()).thenReturn(new Location(world, 20, 64, 20));
    when(move.getTo()).thenReturn(new Location(world, 21, 64, 20));
    manager.move(move);
    verify(move, never()).setCancelled(true);
    PlayerDropItemEvent drop = mock(PlayerDropItemEvent.class);
    Item dropped = mock(Item.class);
    ItemStack ordinaryItem = mock(ItemStack.class);
    when(dropped.getItemStack()).thenReturn(ordinaryItem);
    when(drop.getItemDrop()).thenReturn(dropped);
    when(drop.getPlayer()).thenReturn(player);
    manager.drop(drop);
    verify(drop, never()).setCancelled(true);
  }

  private void tick() {
    int current = mainQueue.size();
    for (int index = 0; index < current; index++) mainQueue.removeFirst().run();
  }

  private void field(String name, Object value) throws Exception {
    var field = CommunityServiceManager.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(manager, value);
  }

  @SuppressWarnings("unchecked")
  private Set<UUID> playerSet(String name) throws Exception {
    var field = CommunityServiceManager.class.getDeclaredField(name);
    field.setAccessible(true);
    return (Set<UUID>) field.get(manager);
  }
}
