package cloud.mineplay.punishments.communityservice;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cloud.mineplay.punishments.punishment.Models.Sentence;
import cloud.mineplay.punishments.punishment.PunishmentService;
import cloud.mineplay.punishments.storage.Database;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

/** Exercises manager reload/validation/setup/stop with the real chunk-ticket lease. */
class QuarryChunkLifecycleTest {
  @TempDir Path folder;
  private JavaPlugin plugin;
  private YamlConfiguration config;
  private CommunityServiceManager manager;
  private PunishmentService service;
  private World world;
  private Block stone;
  private BukkitTask tickTask;
  private MockedStatic<Bukkit> bukkit;
  private MockedStatic<HandlerList> handlers;
  private MockedConstruction<ServiceRepository> repositories;
  private final Set<String> tickets = new HashSet<>();
  private final Set<String> loaded = new HashSet<>();
  private final List<String> changes = new ArrayList<>();
  private final AtomicInteger maximumHeld = new AtomicInteger();

  @BeforeEach
  void setup() throws Exception {
    try (var stream = getClass().getResourceAsStream("/messages.yml")) {
      Files.copy(stream, folder.resolve("messages.yml"));
    }
    plugin = mock(JavaPlugin.class);
    when(plugin.namespace()).thenReturn("mineplaypunishments");
    when(plugin.getDataFolder()).thenReturn(folder.toFile());
    when(plugin.getLogger()).thenReturn(mock(Logger.class));
    config = new YamlConfiguration();
    when(plugin.getConfig()).thenReturn(config);
    config.set("community-service.enabled", true);
    config.set("community-service.quarry.world", "world");
    point("quarry.pos1", 0, 64, 0);
    point("quarry.pos2", 1, 64, 1);
    point("quarry.arrival", 0.5, 65, 0.5);
    point("quarry.release", 32.5, 65, 32.5);
    point("quarry.fallback", 48.5, 65, 48.5);

    world = mock(World.class);
    UUID worldId = UUID.randomUUID();
    when(world.getName()).thenReturn("world");
    when(world.getUID()).thenReturn(worldId);
    when(world.getMinHeight()).thenReturn(-64);
    when(world.getMaxHeight()).thenReturn(320);
    when(world.isChunkGenerated(anyInt(), anyInt())).thenReturn(true);
    when(world.isChunkLoaded(anyInt(), anyInt()))
        .thenAnswer(call -> loaded.contains(key(call.getArgument(0), call.getArgument(1))));
    when(world.addPluginChunkTicket(anyInt(), anyInt(), eq(plugin)))
        .thenAnswer(
            call -> {
              String key = key(call.getArgument(0), call.getArgument(1));
              changes.add("add:" + key);
              loaded.add(key);
              boolean added = tickets.add(key);
              maximumHeld.accumulateAndGet(tickets.size(), Math::max);
              return added;
            });
    when(world.removePluginChunkTicket(anyInt(), anyInt(), eq(plugin)))
        .thenAnswer(
            call -> {
              String key = key(call.getArgument(0), call.getArgument(1));
              changes.add("remove:" + key);
              boolean removed = tickets.remove(key);
              if (removed) loaded.remove(key);
              return removed;
            });
    stone = mock(Block.class);
    when(stone.getType()).thenReturn(Material.STONE);
    when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(stone);
    Material solid = mock(Material.class), air = mock(Material.class);
    when(solid.isSolid()).thenReturn(true);
    Block floor = mock(Block.class), clear = mock(Block.class);
    when(floor.getType()).thenReturn(solid);
    when(clear.getType()).thenReturn(air);
    when(clear.isPassable()).thenReturn(true);
    when(world.getBlockAt(any(Location.class)))
        .thenAnswer(call -> ((Location) call.getArgument(0)).getBlockY() == 64 ? floor : clear);

    bukkit = mockStatic(Bukkit.class);
    bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
    bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
    bukkit.when(() -> Bukkit.getWorld(worldId)).thenReturn(world);
    bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of());
    bukkit.when(Bukkit::getPluginManager).thenReturn(mock(PluginManager.class));
    BukkitScheduler scheduler = mock(BukkitScheduler.class);
    tickTask = mock(BukkitTask.class);
    when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), eq(1L), eq(1L)))
        .thenReturn(tickTask);
    bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
    handlers = mockStatic(HandlerList.class);
    repositories =
        mockConstruction(
            ServiceRepository.class,
            (repository, context) -> {
              when(repository.initialize()).thenReturn(CompletableFuture.completedFuture(null));
              when(repository.regenerations())
                  .thenReturn(CompletableFuture.completedFuture(List.of()));
            });
    service = mock(PunishmentService.class);
    manager = new CommunityServiceManager(plugin, mock(Database.class), service);
  }

  @AfterEach
  void cleanup() {
    if (repositories != null) repositories.close();
    if (handlers != null) handlers.close();
    if (bukkit != null) bukkit.close();
  }

  @Test
  void startupLoadsExistingChunksAndStopReleasesEveryOwnedTicket() {
    manager.start();

    assertTrue(manager.ready());
    assertEquals(Set.of("0:0", "2:2", "3:3"), tickets);
    assertEquals(tickets, loaded);
    verify(repositories.constructed().getFirst()).initialize();

    manager.stop();
    assertFalse(manager.ready());
    assertTrue(tickets.isEmpty());
    assertTrue(loaded.isEmpty());
    verify(tickTask).cancel();
    assertEquals(3, changes.stream().filter(change -> change.startsWith("remove:")).count());
  }

  @Test
  void repeatedReloadAndAdminValidationReplaceTicketsWithoutAccumulation() {
    CommandSender staff = mock(CommandSender.class);
    when(staff.hasPermission("mineplaypunishments.admin.quarry")).thenReturn(true);
    manager.reload();
    for (int i = 0; i < 3; i++) {
      changes.clear();
      manager.adminQuarry(staff, "validate");
      assertTrue(manager.ready());
      assertEquals(Set.of("0:0", "2:2", "3:3"), tickets);
      assertEquals(6, changes.size());
      assertTrue(changes.subList(0, 3).stream().allMatch(change -> change.startsWith("remove:")));
      assertTrue(changes.subList(3, 6).stream().allMatch(change -> change.startsWith("add:")));
    }
    assertEquals(3, maximumHeld.get());
  }

  @Test
  void changedDestinationsReleaseOldChunksAndPinTheNewLayout() {
    manager.reload();
    point("quarry.release", 64.5, 65, 64.5);
    manager.reload();

    assertTrue(manager.ready());
    assertEquals(Set.of("0:0", "3:3", "4:4"), tickets);
    assertFalse(loaded.contains("2:2"));
    assertEquals(3, maximumHeld.get());
  }

  @Test
  void invalidTerrainReleasesTicketsAcquiredDuringFailedValidation() {
    manager.reload();
    Material building = mock(Material.class);
    when(building.isAir()).thenReturn(false);
    when(stone.getType()).thenReturn(building);

    assertThrows(IllegalArgumentException.class, manager::reload);
    assertFalse(manager.ready());
    assertTrue(tickets.isEmpty());
    assertTrue(loaded.isEmpty());
    assertEquals(6, changes.stream().filter(change -> change.startsWith("remove:")).count());
  }

  @Test
  void invalidStaticSettingsReleaseThePriorLayoutWithoutAcquiringAnother() {
    manager.reload();
    changes.clear();
    config.set("community-service.mining.allowed-blocks", List.of("DIAMOND_ORE"));

    assertThrows(IllegalArgumentException.class, manager::reload);
    assertFalse(manager.ready());
    assertTrue(tickets.isEmpty());
    assertEquals(3, changes.size());
    assertTrue(changes.stream().allMatch(change -> change.startsWith("remove:")));
  }

  @ParameterizedTest
  @CsvSource({"community-service.enabled,false", "community-service.quarry.pos1,missing"})
  void disabledOrIncompleteConfigurationReleasesThePriorLayout(String key, String value) {
    manager.reload();
    changes.clear();
    config.set(key, value.equals("false") ? false : null);
    manager.reload();

    assertFalse(manager.ready());
    assertTrue(tickets.isEmpty());
    assertEquals(3, changes.size());
    assertTrue(changes.stream().allMatch(change -> change.startsWith("remove:")));
  }

  @ParameterizedTest
  @CsvSource({
    "quarry,setpos1",
    "quarry,setpos2",
    "quarry,setspawn",
    "quarry,setrelease",
    "quarry,setfallback",
    "prison,setpos1",
    "prison,setpos2",
    "prison,copyquarry"
  })
  void savingAnyLayoutPositionInvalidatesReadinessAndReleasesTickets(
      String kind, String operation) {
    manager.reload();
    Player staff = mock(Player.class);
    when(staff.hasPermission("mineplaypunishments.admin.quarry")).thenReturn(true);
    when(staff.getLocation()).thenReturn(new Location(world, 0.5, 65, 0.5));
    Block selected = mock(Block.class);
    when(selected.getLocation()).thenReturn(new Location(world, 0, 64, 0));
    when(staff.getTargetBlockExact(6)).thenReturn(selected);

    if (kind.equals("quarry")) manager.adminQuarry(staff, operation);
    else manager.adminPrison(staff, operation);

    verify(plugin).saveConfig();
    assertFalse(manager.ready());
    assertTrue(tickets.isEmpty());
    assertTrue(loaded.isEmpty());
  }

  @Test
  void unauthorizedSetupAttemptKeepsTheWorkingLease() {
    manager.reload();
    changes.clear();
    manager.adminQuarry(mock(Player.class), "setpos1");

    assertTrue(manager.ready());
    assertEquals(Set.of("0:0", "2:2", "3:3"), tickets);
    assertTrue(changes.isEmpty());
    verify(plugin, never()).saveConfig();
  }

  @Test
  void preexistingPluginTicketIsNotRemovedByThisManagersStop() {
    tickets.add("0:0");
    loaded.add("0:0");
    manager.reload();
    manager.stop();

    assertFalse(manager.ready());
    assertEquals(Set.of("0:0"), tickets);
    assertEquals(Set.of("0:0"), loaded);
    verify(world, never()).removePluginChunkTicket(0, 0, plugin);
  }

  @Test
  void stoppingTwiceDoesNotAttemptToReleaseTicketsAgain() {
    manager.reload();
    manager.stop();
    changes.clear();
    manager.stop();

    assertFalse(manager.ready());
    assertTrue(changes.isEmpty());
  }

  @Test
  void failedTicketRemovalStillSavesPrisonersAndUnregistersTheManager() {
    manager.start();
    Player prisoner = mock(Player.class);
    UUID playerId = UUID.randomUUID();
    when(prisoner.getUniqueId()).thenReturn(playerId);
    when(service.sentence(playerId))
        .thenReturn(
            Optional.of(
                new Sentence(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    playerId,
                    100,
                    10,
                    "WOODEN_PICKAXE",
                    "ACTIVE")));
    bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(prisoner));
    IllegalStateException failure = new IllegalStateException("Ticket removal failed");
    doThrow(failure).when(world).removePluginChunkTicket(0, 0, plugin);

    assertDoesNotThrow(manager::stop);

    assertFalse(manager.ready());
    verify(tickTask).cancel();
    verify(prisoner).saveData();
    handlers.verify(() -> HandlerList.unregisterAll(manager));
    verify(plugin.getLogger())
        .log(eq(Level.SEVERE), contains("quarry_chunk_cleanup_failed"), same(failure));
    assertEquals(
        Set.of("0:0"), tickets, "Other tickets are still released after one removal fails");

    doAnswer(
            call -> {
              loaded.remove("0:0");
              return tickets.remove("0:0");
            })
        .when(world)
        .removePluginChunkTicket(0, 0, plugin);
    assertDoesNotThrow(manager::stop);
    assertTrue(tickets.isEmpty(), "The failed ticket remains owned for a later cleanup retry");
  }

  @Test
  void unloadedUngeneratedDestinationFailsClosedAndReleasesPartialAcquisition() {
    when(world.isChunkGenerated(2, 2)).thenReturn(false);
    manager.start();

    assertFalse(manager.ready());
    assertTrue(tickets.isEmpty());
    assertTrue(loaded.isEmpty());
    verify(world, never()).addPluginChunkTicket(2, 2, plugin);
    assertEquals(List.of("add:0:0", "remove:0:0"), changes);
  }

  private static String key(int x, int z) {
    return x + ":" + z;
  }

  private void point(String path, Number x, Number y, Number z) {
    config.set("community-service." + path + ".x", x);
    config.set("community-service." + path + ".y", y);
    config.set("community-service." + path + ".z", z);
  }
}
