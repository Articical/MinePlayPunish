package cloud.mineplay.punishments.communityservice;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cloud.mineplay.punishments.punishment.Models.Sentence;
import cloud.mineplay.punishments.punishment.PunishmentService;
import cloud.mineplay.punishments.storage.Database;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Full-prison protection is independent of whether the mining setup is safe to admit prisoners. */
class PrisonProtectionTest {
  private CommunityServiceManager manager;
  private Player visitor;
  private PunishmentService service;
  private World world;
  private QuarryConfiguration quarry;
  private final QuarryBounds mine = new QuarryBounds(0, 64, 0, 5, 67, 5);
  private final QuarryBounds prison = new QuarryBounds(-5, 60, -5, 10, 75, 10);

  enum ProtectionSource {
    VALIDATED_QUARRY,
    CONFIGURED_BOUNDS_WITH_UNREADY_QUARRY
  }

  @BeforeEach
  void setup() {
    JavaPlugin plugin = mock(JavaPlugin.class);
    when(plugin.namespace()).thenReturn("mineplaypunishments");
    service = mock(PunishmentService.class);
    when(service.sentence(any(UUID.class))).thenReturn(Optional.empty());
    manager =
        new CommunityServiceManager(
            plugin, new Database(Path.of("unused-protection-test.db")), service);
    visitor = mock(Player.class);
    when(visitor.getUniqueId()).thenReturn(UUID.randomUUID());
    world = mock(World.class);
    when(world.getUID()).thenReturn(UUID.randomUUID());
    quarry =
        new QuarryConfiguration(
            world,
            mine,
            prison,
            true,
            new Location(world, 8, 64, 8),
            new Location(world, 20, 64, 20),
            new Location(world, 30, 64, 30),
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
  }

  private void configure(ProtectionSource source) throws Exception {
    field("quarry", source == ProtectionSource.VALIDATED_QUARRY ? quarry : null);
    field(
        "protection",
        source == ProtectionSource.VALIDATED_QUARRY
            ? List.of()
            : List.of(new PrisonProtection(world.getUID(), prison)));
    field("ready", source == ProtectionSource.VALIDATED_QUARRY);
  }

  @ParameterizedTest
  @EnumSource(ProtectionSource.class)
  void visitorsAndOperatorsWithoutBuildPermissionCannotBreakAnyBlockInPrisonOrMine(
      ProtectionSource source) throws Exception {
    configure(source);
    for (boolean operator : List.of(false, true)) {
      when(visitor.isOp()).thenReturn(operator);
      when(visitor.hasPermission("mineplaypunishments.prison.build")).thenReturn(false);
      for (Material type :
          List.of(
              Material.STONE,
              Material.DIAMOND_ORE,
              Material.CHEST,
              Material.OAK_PLANKS,
              Material.DIRT)) {
        for (int coordinate : List.of(2, 8)) {
          BlockBreakEvent event = breakEvent(block(coordinate, 64, coordinate, type));
          manager.breakBlock(event);
          verify(event).setCancelled(true);
          verify(event, never()).setDropItems(false);
        }
      }
    }
  }

  @ParameterizedTest
  @EnumSource(ProtectionSource.class)
  void staffBuildPermissionAllowsOrdinaryBlockActionsWithoutChangingDropsOrExperience(
      ProtectionSource source) throws Exception {
    configure(source);
    when(visitor.hasPermission("mineplaypunishments.prison.build")).thenReturn(true);
    for (int coordinate : List.of(2, 8)) {
      for (Material material : List.of(Material.STONE, Material.CHEST, Material.OAK_PLANKS)) {
        Block block = block(coordinate, 64, coordinate, material);
        BlockBreakEvent breaking = breakEvent(block);
        manager.breakBlock(breaking);
        verify(breaking, never()).setCancelled(anyBoolean());
        verify(breaking, never()).setDropItems(anyBoolean());
        verify(breaking, never()).setExpToDrop(anyInt());
        manager.finaliseBreak(breaking);
        assertOrdinaryActions(block, false);
      }
    }
    verify(visitor, never()).getInventory();
  }

  @ParameterizedTest
  @EnumSource(ProtectionSource.class)
  void buildPermissionCannotBypassActiveOrRecoveryCustody(ProtectionSource source)
      throws Exception {
    configure(source);
    when(visitor.hasPermission("mineplaypunishments.prison.build")).thenReturn(true);
    PlayerInventory inventory = mock(PlayerInventory.class);
    ItemStack held = mock(ItemStack.class);
    when(held.getType()).thenReturn(Material.DIAMOND_PICKAXE);
    when(inventory.getItemInMainHand()).thenReturn(held);
    when(visitor.getInventory()).thenReturn(inventory);
    for (String state :
        List.of("PENDING", "SNAPSHOTTED", "ACTIVE", "WORK_COMPLETE", "RESTORING", "RECOVERY")) {
      Sentence sentence =
          new Sentence(
              UUID.randomUUID(),
              UUID.randomUUID(),
              visitor.getUniqueId(),
              10,
              0,
              "WOODEN_PICKAXE",
              state);
      when(service.sentence(visitor.getUniqueId())).thenReturn(Optional.of(sentence));
      for (int coordinate : List.of(2, 8, 20)) {
        Block block = block(coordinate, 64, coordinate, Material.CHEST);
        BlockBreakEvent breaking = breakEvent(block);
        manager.breakBlock(breaking);
        verify(breaking).setCancelled(true);
        verify(breaking, never()).setCancelled(false);
        verify(breaking, never()).setDropItems(anyBoolean());
        verify(breaking, never()).setExpToDrop(anyInt());
        assertOrdinaryActions(block, true);
      }
    }
  }

  @ParameterizedTest
  @EnumSource(ProtectionSource.class)
  void buildPermissionCannotBypassAnInFlightCustodyTransition(ProtectionSource source)
      throws Exception {
    configure(source);
    when(visitor.hasPermission("mineplaypunishments.prison.build")).thenReturn(true);
    field("processing", Set.of(visitor.getUniqueId()));
    Block wall = block(8, 64, 8, Material.STONE);
    BlockBreakEvent breaking = breakEvent(wall);
    manager.breakBlock(breaking);
    verify(breaking).setCancelled(true);
    assertOrdinaryActions(wall, true);
  }

  @ParameterizedTest
  @EnumSource(ProtectionSource.class)
  void grantedBuildPermissionPreservesOtherPluginsCancellation(ProtectionSource source)
      throws Exception {
    configure(source);
    when(visitor.hasPermission("mineplaypunishments.prison.build")).thenReturn(true);
    Block wall = block(8, 64, 8, Material.STONE);
    BlockBreakEvent breaking = breakEvent(wall);
    when(breaking.isCancelled()).thenReturn(true);
    manager.breakBlock(breaking);
    assertCancellationUnchanged(breaking);
    verify(breaking, never()).setDropItems(anyBoolean());
    verify(breaking, never()).setExpToDrop(anyInt());
    BlockPlaceEvent place = placeEvent(wall);
    when(place.isCancelled()).thenReturn(true);
    manager.place(place);
    assertCancellationUnchanged(place);
    BlockDropItemEvent drops = dropsEvent(wall);
    when(drops.isCancelled()).thenReturn(true);
    manager.blockDrops(drops);
    assertCancellationUnchanged(drops);
    PlayerBucketFillEvent fill = fillEvent(wall);
    when(fill.isCancelled()).thenReturn(true);
    manager.bucketFill(fill);
    assertCancellationUnchanged(fill);
    PlayerBucketEmptyEvent empty = emptyEvent(wall);
    when(empty.isCancelled()).thenReturn(true);
    manager.bucketEmpty(empty);
    assertCancellationUnchanged(empty);
    // Paper skips these handlers for an already cancelled event in actual dispatch.
    for (String method :
        List.of("breakBlock", "place", "blockDrops", "bucketFill", "bucketEmpty")) {
      var handler =
          java.util.Arrays.stream(CommunityServiceManager.class.getMethods())
              .filter(candidate -> candidate.getName().equals(method))
              .findFirst()
              .orElseThrow();
      assertTrue(handler.getAnnotation(EventHandler.class).ignoreCancelled());
    }
  }

  @ParameterizedTest
  @EnumSource(ProtectionSource.class)
  void removingBuildPermissionAppliesImmediatelyToTheNextAction(ProtectionSource source)
      throws Exception {
    configure(source);
    Block wall = block(8, 64, 8, Material.STONE);
    when(visitor.hasPermission("mineplaypunishments.prison.build")).thenReturn(true);
    assertOrdinaryActions(wall, false);
    when(visitor.hasPermission("mineplaypunishments.prison.build")).thenReturn(false);
    assertOrdinaryActions(wall, true);
    BlockBreakEvent breaking = breakEvent(wall);
    manager.breakBlock(breaking);
    verify(breaking).setCancelled(true);
  }

  @ParameterizedTest
  @EnumSource(ProtectionSource.class)
  void visitorsCannotPlaceOrReceiveBlockDropsInsideThePrison(ProtectionSource source)
      throws Exception {
    configure(source);
    Block wall = block(8, 64, 8, Material.STONE);
    BlockPlaceEvent place = mock(BlockPlaceEvent.class);
    when(place.getPlayer()).thenReturn(visitor);
    when(place.getBlockPlaced()).thenReturn(wall);
    manager.place(place);
    verify(place).setCancelled(true);
    BlockDropItemEvent drops = mock(BlockDropItemEvent.class);
    when(drops.getPlayer()).thenReturn(visitor);
    when(drops.getBlock()).thenReturn(wall);
    manager.blockDrops(drops);
    verify(drops).setCancelled(true);
  }

  @ParameterizedTest
  @EnumSource(ProtectionSource.class)
  void bucketChangesInPrisonAreBlocked(ProtectionSource source) throws Exception {
    configure(source);
    Block wall = block(8, 64, 8, Material.STONE);
    PlayerBucketFillEvent fill = mock(PlayerBucketFillEvent.class);
    when(fill.getPlayer()).thenReturn(visitor);
    when(fill.getBlock()).thenReturn(wall);
    manager.bucketFill(fill);
    verify(fill).setCancelled(true);
    PlayerBucketEmptyEvent empty = mock(PlayerBucketEmptyEvent.class);
    when(empty.getPlayer()).thenReturn(visitor);
    when(empty.getBlock()).thenReturn(wall);
    manager.bucketEmpty(empty);
    verify(empty).setCancelled(true);
  }

  @ParameterizedTest
  @EnumSource(ProtectionSource.class)
  void ordinaryBlockActionsOutsidePrisonRemainUnaffected(ProtectionSource source) throws Exception {
    configure(source);
    Block outside = block(20, 64, 20, Material.STONE);
    BlockBreakEvent breaking = breakEvent(outside);
    manager.breakBlock(breaking);
    verify(breaking, never()).setCancelled(true);
    BlockPlaceEvent place = mock(BlockPlaceEvent.class);
    when(place.getPlayer()).thenReturn(visitor);
    when(place.getBlockPlaced()).thenReturn(outside);
    manager.place(place);
    verify(place, never()).setCancelled(true);
    PlayerBucketFillEvent fill = mock(PlayerBucketFillEvent.class);
    when(fill.getPlayer()).thenReturn(visitor);
    when(fill.getBlock()).thenReturn(outside);
    manager.bucketFill(fill);
    verify(fill, never()).setCancelled(true);
    PlayerBucketEmptyEvent empty = mock(PlayerBucketEmptyEvent.class);
    when(empty.getPlayer()).thenReturn(visitor);
    when(empty.getBlock()).thenReturn(outside);
    manager.bucketEmpty(empty);
    verify(empty, never()).setCancelled(true);
  }

  @ParameterizedTest
  @EnumSource(ProtectionSource.class)
  void protectionDoesNotApplyAtTheSameCoordinatesInAnotherWorld(ProtectionSource source)
      throws Exception {
    configure(source);
    World otherWorld = mock(World.class);
    when(otherWorld.getUID()).thenReturn(UUID.randomUUID());
    Block other = block(8, 64, 8, Material.STONE);
    when(other.getWorld()).thenReturn(otherWorld);
    when(other.getLocation()).thenReturn(new Location(otherWorld, 8, 64, 8));
    BlockBreakEvent event = breakEvent(other);
    manager.breakBlock(event);
    verify(event, never()).setCancelled(true);
  }

  @ParameterizedTest
  @EnumSource(ProtectionSource.class)
  void entityAndBlockExplosionsKeepPrisonAndMiningBlocksIntact(ProtectionSource source)
      throws Exception {
    configure(source);
    Block miningBlock = block(2, 64, 2, Material.STONE);
    Block prisonWall = block(8, 64, 8, Material.OAK_PLANKS);
    Block outside = block(20, 64, 20, Material.DIRT);
    List<Block> entityBlocks = new ArrayList<>(List.of(miningBlock, prisonWall, outside));
    EntityExplodeEvent entity = mock(EntityExplodeEvent.class);
    when(entity.blockList()).thenReturn(entityBlocks);
    manager.explosion(entity);
    assertEquals(List.of(outside), entityBlocks);
    List<Block> blockBlocks = new ArrayList<>(List.of(miningBlock, prisonWall, outside));
    BlockExplodeEvent block = mock(BlockExplodeEvent.class);
    when(block.blockList()).thenReturn(blockBlocks);
    manager.explosion(block);
    assertEquals(List.of(outside), blockBlocks);
  }

  @ParameterizedTest
  @EnumSource(ProtectionSource.class)
  void pistonsCannotMoveBlocksAcrossThePrisonBoundaryInEitherDirection(ProtectionSource source)
      throws Exception {
    configure(source);
    for (boolean enters : List.of(false, true)) {
      Block origin = block(enters ? 11 : 10, 64, 8, Material.STONE);
      Block destination = block(enters ? 10 : 11, 64, 8, Material.STONE);
      BlockFace direction = enters ? BlockFace.WEST : BlockFace.EAST;
      when(origin.getRelative(direction)).thenReturn(destination);
      BlockPistonExtendEvent extend = mock(BlockPistonExtendEvent.class);
      when(extend.getBlocks()).thenReturn(List.of(origin));
      when(extend.getDirection()).thenReturn(direction);
      manager.piston(extend);
      verify(extend).setCancelled(true);
      BlockPistonRetractEvent retract = mock(BlockPistonRetractEvent.class);
      when(retract.getBlocks()).thenReturn(List.of(origin));
      when(retract.getDirection()).thenReturn(direction);
      manager.piston(retract);
      verify(retract).setCancelled(true);
    }
  }

  @ParameterizedTest
  @EnumSource(ProtectionSource.class)
  void fluidsCannotEnterOrLeavePrisonWhileOutsideFlowContinues(ProtectionSource source)
      throws Exception {
    configure(source);
    Block inside = block(10, 64, 8, Material.WATER);
    Block outside = block(11, 64, 8, Material.AIR);
    for (boolean enters : List.of(false, true)) {
      BlockFromToEvent flow = mock(BlockFromToEvent.class);
      when(flow.getBlock()).thenReturn(enters ? outside : inside);
      when(flow.getToBlock()).thenReturn(enters ? inside : outside);
      manager.fluid(flow);
      verify(flow).setCancelled(true);
    }
    BlockFromToEvent ordinary = mock(BlockFromToEvent.class);
    Block outsideDestination = block(12, 64, 8, Material.AIR);
    when(ordinary.getBlock()).thenReturn(outside);
    when(ordinary.getToBlock()).thenReturn(outsideDestination);
    manager.fluid(ordinary);
    verify(ordinary, never()).setCancelled(true);
  }

  @ParameterizedTest
  @EnumSource(ProtectionSource.class)
  void pistonsOutsideThePrisonRemainUnaffected(ProtectionSource source) throws Exception {
    configure(source);
    Block origin = block(20, 64, 20, Material.STONE);
    Block destination = block(21, 64, 20, Material.AIR);
    when(origin.getRelative(BlockFace.EAST)).thenReturn(destination);
    BlockPistonExtendEvent extend = mock(BlockPistonExtendEvent.class);
    when(extend.getBlocks()).thenReturn(List.of(origin));
    when(extend.getDirection()).thenReturn(BlockFace.EAST);
    manager.piston(extend);
    verify(extend, never()).setCancelled(true);
    BlockPistonRetractEvent retract = mock(BlockPistonRetractEvent.class);
    when(retract.getBlocks()).thenReturn(List.of(origin));
    when(retract.getDirection()).thenReturn(BlockFace.EAST);
    manager.piston(retract);
    verify(retract, never()).setCancelled(true);
  }

  @ParameterizedTest
  @EnumSource(ProtectionSource.class)
  void entitiesCannotChangePrisonBlocksButCanChangeOutsideBlocks(ProtectionSource source)
      throws Exception {
    configure(source);
    Block insideBlock = block(8, 64, 8, Material.STONE);
    EntityChangeBlockEvent inside = mock(EntityChangeBlockEvent.class);
    when(inside.getBlock()).thenReturn(insideBlock);
    manager.entityBlock(inside);
    verify(inside).setCancelled(true);
    Block outsideBlock = block(20, 64, 20, Material.DIRT);
    EntityChangeBlockEvent outside = mock(EntityChangeBlockEvent.class);
    when(outside.getBlock()).thenReturn(outsideBlock);
    manager.entityBlock(outside);
    verify(outside, never()).setCancelled(true);
  }

  private BlockBreakEvent breakEvent(Block block) {
    BlockBreakEvent event = mock(BlockBreakEvent.class);
    when(event.getPlayer()).thenReturn(visitor);
    when(event.getBlock()).thenReturn(block);
    return event;
  }

  private void assertOrdinaryActions(Block block, boolean cancelled) {
    BlockPlaceEvent place = placeEvent(block);
    manager.place(place);
    BlockDropItemEvent drops = dropsEvent(block);
    manager.blockDrops(drops);
    PlayerBucketFillEvent fill = fillEvent(block);
    manager.bucketFill(fill);
    PlayerBucketEmptyEvent empty = emptyEvent(block);
    manager.bucketEmpty(empty);
    for (Cancellable event : List.of(place, drops, fill, empty)) {
      if (cancelled) verify(event).setCancelled(true);
      else verify(event, never()).setCancelled(anyBoolean());
      verify(event, never()).setCancelled(false);
    }
    verify(drops, never()).getItems();
  }

  private void assertCancellationUnchanged(Cancellable event) {
    assertTrue(event.isCancelled());
    verify(event, never()).setCancelled(anyBoolean());
  }

  private BlockPlaceEvent placeEvent(Block block) {
    BlockPlaceEvent event = mock(BlockPlaceEvent.class);
    when(event.getPlayer()).thenReturn(visitor);
    when(event.getBlockPlaced()).thenReturn(block);
    return event;
  }

  private BlockDropItemEvent dropsEvent(Block block) {
    BlockDropItemEvent event = mock(BlockDropItemEvent.class);
    when(event.getPlayer()).thenReturn(visitor);
    when(event.getBlock()).thenReturn(block);
    return event;
  }

  private PlayerBucketFillEvent fillEvent(Block block) {
    PlayerBucketFillEvent event = mock(PlayerBucketFillEvent.class);
    when(event.getPlayer()).thenReturn(visitor);
    when(event.getBlock()).thenReturn(block);
    return event;
  }

  private PlayerBucketEmptyEvent emptyEvent(Block block) {
    PlayerBucketEmptyEvent event = mock(PlayerBucketEmptyEvent.class);
    when(event.getPlayer()).thenReturn(visitor);
    when(event.getBlock()).thenReturn(block);
    return event;
  }

  private Block block(int x, int y, int z, Material material) {
    Block block = mock(Block.class);
    when(block.getWorld()).thenReturn(world);
    when(block.getX()).thenReturn(x);
    when(block.getY()).thenReturn(y);
    when(block.getZ()).thenReturn(z);
    when(block.getLocation()).thenReturn(new Location(world, x, y, z));
    when(block.getType()).thenReturn(material);
    return block;
  }

  private void field(String name, Object value) throws Exception {
    var field = CommunityServiceManager.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(manager, value);
  }
}
