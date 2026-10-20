package cloud.mineplay.punishments.communityservice;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cloud.mineplay.punishments.punishment.Models.Sentence;
import cloud.mineplay.punishments.punishment.PunishmentService;
import cloud.mineplay.punishments.storage.Database;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

class PrisonRestrictionsTest {
  private CommunityServiceManager manager;
  private Player player;
  private PunishmentService service;
  private JavaPlugin plugin;
  private World world;

  @BeforeEach
  void setup() {
    plugin = mock(JavaPlugin.class);
    when(plugin.namespace()).thenReturn("mineplaypunishments");
    service = mock(PunishmentService.class);
    player = mock(Player.class);
    UUID id = UUID.randomUUID();
    when(player.getUniqueId()).thenReturn(id);
    when(service.sentence(id))
        .thenReturn(
            Optional.of(
                new Sentence(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    id,
                    10,
                    0,
                    "WOODEN_PICKAXE",
                    "RECOVERY")));
    manager = new CommunityServiceManager(plugin, new Database(Path.of("unused-test.db")), service);
  }

  @Test
  void recoveryRestrictsTeleportEvenWithoutAQuarry() {
    PlayerTeleportEvent event = mock(PlayerTeleportEvent.class);
    when(event.getPlayer()).thenReturn(player);
    manager.teleportEvent(event);
    verify(event).setCancelled(true);
  }

  @Test
  void recoveryPreventsDroppingSwappingAndExternalMenus() {
    PlayerDropItemEvent drop = mock(PlayerDropItemEvent.class);
    when(drop.getPlayer()).thenReturn(player);
    manager.drop(drop);
    verify(drop).setCancelled(true);
    PlayerSwapHandItemsEvent swap = mock(PlayerSwapHandItemsEvent.class);
    when(swap.getPlayer()).thenReturn(player);
    manager.swap(swap);
    verify(swap).setCancelled(true);
    InventoryOpenEvent open = mock(InventoryOpenEvent.class);
    when(open.getPlayer()).thenReturn(player);
    manager.inventoryOpen(open);
    verify(open).setCancelled(true);
    InventoryClickEvent click = mock(InventoryClickEvent.class);
    when(click.getWhoClicked()).thenReturn(player);
    manager.inventoryClick(click);
    verify(click).setCancelled(true);
  }

  @Test
  void recoveryProtectsFromEnvironmentalDamageAndGamemodeEscape() {
    EntityDamageEvent damage = mock(EntityDamageEvent.class);
    when(damage.getEntity()).thenReturn(player);
    manager.damage(damage);
    verify(damage).setCancelled(true);
    PlayerGameModeChangeEvent game = mock(PlayerGameModeChangeEvent.class);
    when(game.getPlayer()).thenReturn(player);
    manager.gameMode(game);
    verify(game).setCancelled(true);
  }

  @Test
  void namespacedTeleportCommandIsBlockedWhileProgressRemainsAvailable() {
    PlayerCommandPreprocessEvent escape = mock(PlayerCommandPreprocessEvent.class);
    when(escape.getPlayer()).thenReturn(player);
    when(escape.getMessage()).thenReturn("/essentials:home");
    manager.command(escape);
    verify(escape).setCancelled(true);
    PlayerCommandPreprocessEvent progress = mock(PlayerCommandPreprocessEvent.class);
    when(progress.getPlayer()).thenReturn(player);
    when(progress.getMessage()).thenReturn("/mineplaypunishments:service progress");
    manager.command(progress);
    verify(progress, never()).setCancelled(true);
  }

  private BlockBreakEvent eligibleBreak() throws Exception {
    Sentence sentence =
        new Sentence(
            UUID.randomUUID(),
            UUID.randomUUID(),
            player.getUniqueId(),
            10,
            0,
            "WOODEN_PICKAXE",
            "ACTIVE");
    when(service.sentence(player.getUniqueId())).thenReturn(Optional.of(sentence));
    when(service.healthy()).thenReturn(true);
    world = mock(World.class);
    when(world.getUID()).thenReturn(UUID.randomUUID());
    Location point = new Location(world, 0, 64, 0);
    QuarryConfiguration quarry =
        new QuarryConfiguration(
            world,
            new QuarryBounds(0, 64, 0, 5, 67, 5),
            new QuarryBounds(-5, 60, -5, 10, 75, 10),
            true,
            new Location(world, 8, 64, 8),
            point,
            point,
            Set.of(Material.STONE),
            Set.of(Material.WOODEN_PICKAXE),
            Material.STONE,
            60,
            20,
            10,
            50,
            true,
            true,
            List.of("service progress"));
    field("quarry", quarry);
    field("ready", true);
    playerSet("prepared").add(player.getUniqueId());
    when(player.isOnline()).thenReturn(true);
    PlayerInventory inventory = mock(PlayerInventory.class);
    when(player.getInventory()).thenReturn(inventory);
    when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
    ItemStack pickaxe = mock(ItemStack.class);
    when(inventory.getItemInMainHand()).thenReturn(pickaxe);
    when(pickaxe.getType()).thenReturn(Material.WOODEN_PICKAXE);
    when(pickaxe.hasItemMeta()).thenReturn(true);
    ItemMeta meta = mock(ItemMeta.class);
    when(pickaxe.getItemMeta()).thenReturn(meta);
    PersistentDataContainer pdc = mock(PersistentDataContainer.class);
    when(meta.getPersistentDataContainer()).thenReturn(pdc);
    when(pdc.get(any(NamespacedKey.class), eq(PersistentDataType.STRING)))
        .thenReturn(sentence.id().toString());
    Block block = mock(Block.class);
    when(block.getWorld()).thenReturn(world);
    when(block.getY()).thenReturn(64);
    when(block.getLocation()).thenReturn(point);
    when(block.getType()).thenReturn(Material.STONE);
    BlockBreakEvent event = mock(BlockBreakEvent.class);
    when(event.getPlayer()).thenReturn(player);
    when(event.getBlock()).thenReturn(block);
    return event;
  }

  @Test
  void validMiningSuppressesDropsXpAndReservesPosition() throws Exception {
    BlockBreakEvent first = eligibleBreak();
    manager.breakBlock(first);
    verify(first).setDropItems(false);
    verify(first).setExpToDrop(0);
    verify(first, never()).setCancelled(true);
    BlockBreakEvent concurrent = mock(BlockBreakEvent.class);
    Block sharedBlock = first.getBlock();
    when(concurrent.getPlayer()).thenReturn(player);
    when(concurrent.getBlock()).thenReturn(sharedBlock);
    manager.breakBlock(concurrent);
    verify(concurrent).setCancelled(true);
  }

  @Test
  void wrongToolAndCreativeModeReceiveNoCredit() throws Exception {
    BlockBreakEvent first = eligibleBreak();
    when(player.getGameMode()).thenReturn(GameMode.CREATIVE);
    manager.breakBlock(first);
    verify(first).setCancelled(true);
    verify(first, never()).setDropItems(false);
    BlockBreakEvent wrong = eligibleBreak();
    when(player.getInventory().getItemInMainHand().getType()).thenReturn(Material.DIAMOND_PICKAXE);
    manager.breakBlock(wrong);
    verify(wrong).setCancelled(true);
  }

  @Test
  void prisonerCanWalkThroughThePrisonOutsideTheMiningCuboid() throws Exception {
    eligibleBreak();
    PlayerMoveEvent event = move(new Location(world, 2, 68, 2), new Location(world, 8, 64, 8));
    manager.move(event);
    verify(event, never()).setCancelled(true);
  }

  @Test
  void prisonerCannotWalkOutsideAnyFaceOfThePrison() throws Exception {
    eligibleBreak();
    for (Location destination :
        List.of(
            new Location(world, -5.01, 64, 0), new Location(world, 11, 64, 0),
            new Location(world, 0, 59.99, 0), new Location(world, 0, 76, 0),
            new Location(world, 0, 64, -5.01), new Location(world, 0, 64, 11))) {
      PlayerMoveEvent event = move(new Location(world, 0, 68, 0), destination);
      manager.move(event);
      verify(event).setCancelled(true);
    }
  }

  @Test
  void quarryExtraHeadroomDoesNotExtendAnExplicitPrisonBoundary() throws Exception {
    eligibleBreak();
    PlayerMoveEvent event = move(new Location(world, 0, 75, 0), new Location(world, 0, 76, 0));
    manager.move(event);
    verify(event).setCancelled(true);
  }

  @Test
  void prisonMovementRejectsAnotherWorldButPermitsLookingAroundWhileFrozen() throws Exception {
    eligibleBreak();
    World other = mock(World.class);
    when(other.getUID()).thenReturn(UUID.randomUUID());
    PlayerMoveEvent crossWorld = move(new Location(world, 0, 68, 0), new Location(other, 8, 64, 8));
    manager.move(crossWorld);
    verify(crossWorld).setCancelled(true);
    when(service.healthy()).thenReturn(false);
    PlayerMoveEvent look =
        move(new Location(world, 8, 64, 8, 0, 0), new Location(world, 8, 64, 8, 90, 20));
    manager.move(look);
    verify(look, never()).setCancelled(true);
  }

  @Test
  void prisonerCannotMineAStonePrisonWallOutsideTheQuarry() throws Exception {
    BlockBreakEvent wall = eligibleBreak();
    Block block = wall.getBlock();
    when(block.getX()).thenReturn(8);
    when(block.getLocation()).thenReturn(new Location(world, 8, 64, 0));
    manager.breakBlock(wall);
    verify(wall).setCancelled(true);
    verify(wall, never()).setDropItems(false);
  }

  @ParameterizedTest
  @EnumSource(
      value = Material.class,
      names = {"DIAMOND_ORE", "CHEST", "OAK_PLANKS", "DIRT", "IRON_BLOCK", "AIR"})
  void prisonerCannotMineNonStoneEvenInsideTheQuarry(Material material) throws Exception {
    BlockBreakEvent event = eligibleBreak();
    when(event.getBlock().getType()).thenReturn(material);
    manager.breakBlock(event);
    verify(event).setCancelled(true);
    verify(event, never()).setDropItems(false);
  }

  @ParameterizedTest
  @ValueSource(strings = {"PENDING", "RECOVERY", "RESTORING", "RELEASING", "COMPLETED"})
  void onlyAnActiveSentenceAllowsMining(String state) throws Exception {
    BlockBreakEvent event = eligibleBreak();
    Sentence previous = service.sentence(player.getUniqueId()).orElseThrow();
    when(service.sentence(player.getUniqueId()))
        .thenReturn(
            Optional.of(
                new Sentence(
                    previous.id(),
                    previous.punishmentId(),
                    previous.targetId(),
                    previous.required(),
                    previous.completed(),
                    previous.tool(),
                    state)));
    manager.breakBlock(event);
    verify(event).setCancelled(true);
  }

  @Test
  void processingOrUnavailableStoragePreventsMining() throws Exception {
    BlockBreakEvent event = eligibleBreak();
    playerSet("processing").add(player.getUniqueId());
    manager.breakBlock(event);
    verify(event).setCancelled(true);
    playerSet("processing").clear();
    BlockBreakEvent storage = eligibleBreak();
    when(service.healthy()).thenReturn(false);
    manager.breakBlock(storage);
    verify(storage).setCancelled(true);
  }

  @Test
  void anotherSentenceOrUntaggedPickaxeCannotEarnCredit() throws Exception {
    BlockBreakEvent otherSentence = eligibleBreak();
    var tag = player.getInventory().getItemInMainHand().getItemMeta().getPersistentDataContainer();
    when(tag.get(any(NamespacedKey.class), eq(PersistentDataType.STRING)))
        .thenReturn(UUID.randomUUID().toString());
    manager.breakBlock(otherSentence);
    verify(otherSentence).setCancelled(true);
    BlockBreakEvent untagged = eligibleBreak();
    when(player.getInventory().getItemInMainHand().hasItemMeta()).thenReturn(false);
    manager.breakBlock(untagged);
    verify(untagged).setCancelled(true);
  }

  @Test
  void cancellationAfterMonitorButBeforeTheNextTickCannotCreditOrReserveMining() throws Exception {
    BlockBreakEvent event = eligibleBreak();
    ServiceRepository repository = mock(ServiceRepository.class);
    field("repository", repository);
    // Material.isAir delegates to the live Paper block registry; this is a controller fixture.
    Material removedBlock = mock(Material.class);
    when(removedBlock.isAir()).thenReturn(true);
    ArrayDeque<Runnable> tasks = new ArrayDeque<>();
    BukkitScheduler scheduler = mock(BukkitScheduler.class);
    when(scheduler.runTask(eq(plugin), any(Runnable.class)))
        .thenAnswer(
            call -> {
              tasks.addLast(call.getArgument(1));
              return mock(BukkitTask.class);
            });
    UUID id = player.getUniqueId();
    try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
      bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
      bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(player);
      manager.breakBlock(event);
      manager.finaliseBreak(event);
      assertEquals(1, tasks.size());
      verifyNoInteractions(repository);
      when(event.isCancelled()).thenReturn(true);
      when(event.getBlock().getType()).thenReturn(removedBlock);
      tasks.removeFirst().run();
      verifyNoInteractions(repository);
      when(event.isCancelled()).thenReturn(false);
      when(event.getBlock().getType()).thenReturn(Material.STONE);
      manager.breakBlock(event);
      verify(event, never()).setCancelled(true);
      verify(event, times(2)).setDropItems(false);
    }
  }

  @Test
  void ordinaryPlayersCanMoveAcrossThePrisonBoundary() throws Exception {
    eligibleBreak();
    when(service.sentence(player.getUniqueId())).thenReturn(Optional.empty());
    PlayerMoveEvent event = move(new Location(world, 8, 64, 8), new Location(world, 20, 64, 20));
    manager.move(event);
    verify(event, never()).setCancelled(true);
  }

  private PlayerMoveEvent move(Location from, Location to) {
    PlayerMoveEvent event = mock(PlayerMoveEvent.class);
    when(event.getPlayer()).thenReturn(player);
    when(event.getFrom()).thenReturn(from);
    when(event.getTo()).thenReturn(to);
    return event;
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
