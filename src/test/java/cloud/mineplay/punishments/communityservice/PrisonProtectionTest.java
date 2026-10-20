package cloud.mineplay.punishments.communityservice;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

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
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Full-prison protection is independent of whether the mining setup is safe to admit prisoners. */
class PrisonProtectionTest {
  private CommunityServiceManager manager;
  private Player visitor;
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
    PunishmentService service = mock(PunishmentService.class);
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
  void visitorsAndOperatorsCannotBreakAnyBlockInPrisonOrMine(ProtectionSource source)
      throws Exception {
    configure(source);
    for (boolean operator : List.of(false, true)) {
      when(visitor.isOp()).thenReturn(operator);
      when(visitor.hasPermission(anyString())).thenReturn(operator);
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
