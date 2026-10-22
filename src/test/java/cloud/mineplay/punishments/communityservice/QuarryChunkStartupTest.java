package cloud.mineplay.punishments.communityservice;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

/** Real configuration and location validation against a world whose chunks begin unloaded. */
class QuarryChunkStartupTest {
  private YamlConfiguration config;
  private World world;
  private MockedStatic<Bukkit> bukkit;
  private final Set<String> loaded = new HashSet<>();
  private final List<String> dynamicReads = new ArrayList<>();
  private final List<String> terrainReads = new ArrayList<>();
  private final AtomicInteger preparationCalls = new AtomicInteger();
  private List<Location> destinations;
  private QuarryBounds preparedBounds;
  private int preparedMaximum;

  @BeforeEach
  void setup() {
    config = new YamlConfiguration();
    world = world("world");
    bukkit = mockStatic(Bukkit.class);
    bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
    config.set("community-service.enabled", true);
    config.set("community-service.quarry.world", "world");
    point("quarry.pos1", -1, 64, -1);
    point("quarry.pos2", 1, 64, 1);
    point("quarry.arrival", 0.5, 65, 0.5);
    point("quarry.release", 32.5, 65, 32.5);
    point("quarry.fallback", 48.5, 65, 48.5);
  }

  @AfterEach
  void cleanup() {
    if (bukkit != null) bukkit.close();
  }

  @Test
  void coldQuarryAndDestinationChunksArePreparedBeforeWorldValidation() {
    QuarryConfiguration result = QuarryConfiguration.load(config, this::prepare);

    assertNotNull(result);
    assertEquals(1, preparationCalls.get());
    assertEquals(256, preparedMaximum);
    assertEquals(result.bounds(), preparedBounds);
    assertEquals(List.of(result.arrival(), result.release(), result.fallback()), destinations);
    assertEquals(
        Set.of("world:-1:-1", "world:-1:0", "world:0:-1", "world:0:0", "world:2:2", "world:3:3"),
        loaded);
    assertEquals(9, terrainReads.size());
    assertTrue(QuarryConfiguration.safe(result.arrival()));
    assertTrue(QuarryConfiguration.safe(result.release()));
    assertTrue(QuarryConfiguration.safe(result.fallback()));
    verify(world, never()).getChunkAt(anyInt(), anyInt());
  }

  @Test
  void compatibilityOverloadKeepsValidationOnlyBehaviour() {
    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> QuarryConfiguration.load(config));
    assertTrue(error.getMessage().contains("quarry chunks"));
    assertTrue(loaded.isEmpty());
    assertTrue(terrainReads.isEmpty());
  }

  @Test
  void preparationDoesNotBypassLoadedChunkValidation() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            QuarryConfiguration.load(
                config, (w, bounds, locations, maximum) -> preparationCalls.incrementAndGet()));
    assertEquals(1, preparationCalls.get());
    assertTrue(terrainReads.isEmpty());
  }

  @Test
  void preparationFailureStopsBeforeAnySafeLocationOrTerrainChecks() {
    IllegalArgumentException failure = new IllegalArgumentException("Chunk has not been generated");
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                QuarryConfiguration.load(
                    config,
                    (w, bounds, locations, maximum) -> {
                      preparationCalls.incrementAndGet();
                      throw failure;
                    }));
    assertSame(failure, thrown);
    assertEquals(1, preparationCalls.get());
    assertTrue(dynamicReads.isEmpty());
  }

  @Test
  void largePrisonDoesNotPrepareOrScanEveryPrisonChunk() {
    point("prison.pos1", -1000, 60, -1000);
    point("prison.pos2", 1000, 319, 1000);
    point("quarry.arrival", 200.5, 65, 200.5);
    point("quarry.release", 2000.5, 65, 2000.5);
    point("quarry.fallback", 3000.5, 65, 3000.5);

    QuarryConfiguration result = QuarryConfiguration.load(config, this::prepare);

    assertTrue(result.explicitPrison());
    assertTrue(result.prisonBounds().volume() > 50_000);
    assertFalse(result.contains(result.arrival()));
    assertTrue(result.containsPrisoner(result.arrival()));
    assertEquals(new QuarryBounds(-1, 64, -1, 1, 64, 1), preparedBounds);
    assertEquals(
        Set.of(
            "world:-1:-1",
            "world:-1:0",
            "world:0:-1",
            "world:0:0",
            "world:12:12",
            "world:125:125",
            "world:187:187"),
        loaded);
    assertEquals(9, terrainReads.size());
    verify(world, never()).isChunkLoaded(-63, -63);
    verify(world, never()).isChunkLoaded(62, 62);
  }

  @Test
  void releaseDestinationsRetainTheirOwnWorldForPreparation() {
    World spawn = world("spawn");
    bukkit.when(() -> Bukkit.getWorld("spawn")).thenReturn(spawn);
    config.set("community-service.quarry.release.world", "spawn");
    config.set("community-service.quarry.fallback.world", "spawn");

    QuarryConfiguration result = QuarryConfiguration.load(config, this::prepare);

    assertSame(world, result.arrival().getWorld());
    assertSame(spawn, result.release().getWorld());
    assertSame(spawn, result.fallback().getWorld());
    assertTrue(loaded.contains("spawn:2:2"));
    assertTrue(loaded.contains("spawn:3:3"));
    assertFalse(loaded.contains("world:2:2"));
    verify(spawn, never()).getBlockAt(anyInt(), anyInt(), anyInt());
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 17, 1024})
  void validChunkLimitsArePassedToPreparation(int maximum) {
    // All three destinations and this small quarry fit within one chunk.
    point("quarry.pos1", 0, 64, 0);
    point("quarry.pos2", 1, 64, 1);
    point("quarry.release", 5.5, 65, 5.5);
    point("quarry.fallback", 10.5, 65, 10.5);
    config.set("community-service.quarry.maximum-loaded-chunks", maximum);

    assertNotNull(QuarryConfiguration.load(config, this::prepare));
    assertEquals(maximum, preparedMaximum);
    assertEquals(Set.of("world:0:0"), loaded);
  }

  @ParameterizedTest(name = "rejects {0} before preparing chunks")
  @MethodSource("invalidSettings")
  void invalidStaticSettingsCannotPrepareChunks(String key, Object value) {
    config.set("community-service." + key, value);
    assertRejectedBeforePreparation();
  }

  static Stream<Arguments> invalidSettings() {
    return Stream.of(
        Arguments.of("quarry.pos1.x", 0.5),
        Arguments.of("quarry.pos2.x", 30_000_001),
        Arguments.of("quarry.pos1.y", -65),
        Arguments.of("quarry.pos2.y", 320),
        Arguments.of("quarry.pos1.world", "missing"),
        Arguments.of("quarry.world", "missing"),
        Arguments.of("mining.allowed-blocks", List.of("DIAMOND_ORE")),
        Arguments.of("equipment.allowed-pickaxes", List.of("DIAMOND_PICKAXE")),
        Arguments.of("mining.regenerated-block", "OAK_PLANKS"),
        Arguments.of("regeneration.delay-ticks", 0),
        Arguments.of("regeneration.max-blocks-per-tick", 0),
        Arguments.of("maximum-prisoners", 0),
        Arguments.of("progress.milestone-interval", 0),
        Arguments.of("quarry.maximum-loaded-chunks", 0),
        Arguments.of("quarry.maximum-loaded-chunks", 1025),
        Arguments.of("quarry.maximum-loaded-chunks", "256"),
        Arguments.of("quarry.maximum-loaded-chunks", 1.5),
        Arguments.of("quarry.arrival.x", Double.NaN),
        Arguments.of("quarry.arrival.x", "oops"),
        Arguments.of("quarry.release.y", "65"),
        Arguments.of("quarry.fallback.z", "48.5"),
        Arguments.of("quarry.release.x", 30_000_000.5),
        Arguments.of("quarry.fallback.z", -30_000_000.5),
        Arguments.of("quarry.release.y", -64),
        Arguments.of("quarry.fallback.y", 319),
        Arguments.of("quarry.release.yaw", Double.POSITIVE_INFINITY),
        Arguments.of("quarry.fallback.pitch", Double.NaN),
        Arguments.of("quarry.release.yaw", "0"),
        Arguments.of("quarry.fallback.pitch", "0"),
        Arguments.of("quarry.release.world", "missing"),
        Arguments.of("quarry.arrival.x", 20.5),
        Arguments.of("restrictions.prevent-teleport-escape", false));
  }

  @Test
  void releaseInsideCustodyIsRejectedBeforePreparation() {
    point("quarry.release", 0.5, 65, 0.5);
    assertRejectedBeforePreparation();
  }

  @Test
  void loadedDestinationsStillNeedClearHeadroomAndSafeFloors() {
    Block obstructed = mock(Block.class);
    doReturn(obstructed).when(world).getBlockAt(any(Location.class));
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class, () -> QuarryConfiguration.load(config, this::prepare));
    assertTrue(error.getMessage().contains("solid floors and clear"));
    assertEquals(1, preparationCalls.get());
    assertTrue(terrainReads.isEmpty());
  }

  @Test
  void oversizedMiningAreaIsRejectedBeforePreparation() {
    point("quarry.pos2", 100, 70, 100);
    assertRejectedBeforePreparation();
  }

  @Test
  void incompletePrisonIsRejectedBeforePreparation() {
    point("prison.pos1", -10, 60, -10);
    assertRejectedBeforePreparation();
  }

  @Test
  void disabledServiceDoesNotPrepareChunks() {
    config.set("community-service.enabled", false);
    assertNull(QuarryConfiguration.load(config, this::prepare));
    assertEquals(0, preparationCalls.get());
    assertTrue(dynamicReads.isEmpty());
  }

  @Test
  void unconfiguredQuarryDoesNotPrepareChunks() {
    config.set("community-service.quarry.pos1", null);
    assertNull(QuarryConfiguration.load(config, this::prepare));
    assertEquals(0, preparationCalls.get());
    assertTrue(dynamicReads.isEmpty());
  }

  private void assertRejectedBeforePreparation() {
    assertThrows(
        IllegalArgumentException.class, () -> QuarryConfiguration.load(config, this::prepare));
    assertEquals(0, preparationCalls.get());
    assertTrue(loaded.isEmpty());
    assertTrue(dynamicReads.isEmpty(), "Invalid configuration must fail before world validation");
    verify(world, never()).getChunkAt(anyInt(), anyInt());
  }

  private void prepare(
      World miningWorld, QuarryBounds bounds, List<Location> locations, int maximum) {
    assertTrue(dynamicReads.isEmpty(), "Preparation must precede chunk, safety and terrain checks");
    assertSame(world, miningWorld);
    preparationCalls.incrementAndGet();
    preparedBounds = bounds;
    preparedMaximum = maximum;
    destinations = List.copyOf(locations);
    for (int x = bounds.minX() >> 4; x <= bounds.maxX() >> 4; x++)
      for (int z = bounds.minZ() >> 4; z <= bounds.maxZ() >> 4; z++)
        loaded.add(key(miningWorld, x, z));
    for (Location location : locations)
      loaded.add(key(location.getWorld(), location.getBlockX() >> 4, location.getBlockZ() >> 4));
  }

  private World world(String name) {
    World value = mock(World.class);
    when(value.getName()).thenReturn(name);
    when(value.getUID()).thenReturn(UUID.randomUUID());
    when(value.getMinHeight()).thenReturn(-64);
    when(value.getMaxHeight()).thenReturn(320);
    when(value.isChunkLoaded(anyInt(), anyInt()))
        .thenAnswer(
            call -> {
              String chunk = key(value, call.getArgument(0), call.getArgument(1));
              dynamicReads.add("chunk:" + chunk);
              return loaded.contains(chunk);
            });
    Block stone = mock(Block.class);
    when(stone.getType()).thenReturn(Material.STONE);
    when(value.getBlockAt(anyInt(), anyInt(), anyInt()))
        .thenAnswer(
            call -> {
              int x = call.getArgument(0), y = call.getArgument(1), z = call.getArgument(2);
              assertTrue(loaded.contains(key(value, x >> 4, z >> 4)));
              String coordinate = name + ":" + x + ":" + y + ":" + z;
              dynamicReads.add("terrain:" + coordinate);
              terrainReads.add(coordinate);
              return stone;
            });
    // Location.getBlock uses the Location overload. Mock material properties here so real
    // safe() checks run without requiring the server's Material registry bootstrap.
    Material solid = mock(Material.class), air = mock(Material.class);
    when(solid.isSolid()).thenReturn(true);
    Block floor = mock(Block.class), clear = mock(Block.class);
    when(floor.getType()).thenReturn(solid);
    when(clear.getType()).thenReturn(air);
    when(clear.isPassable()).thenReturn(true);
    when(value.getBlockAt(any(Location.class)))
        .thenAnswer(
            call -> {
              Location location = call.getArgument(0);
              assertTrue(
                  loaded.contains(
                      key(value, location.getBlockX() >> 4, location.getBlockZ() >> 4)));
              dynamicReads.add("location:" + name + ":" + location.getBlockY());
              return location.getBlockY() == 64 ? floor : clear;
            });
    return value;
  }

  private static String key(World world, int x, int z) {
    return world.getName() + ":" + x + ":" + z;
  }

  private void point(String path, Number x, Number y, Number z) {
    config.set("community-service." + path + ".x", x);
    config.set("community-service." + path + ".y", y);
    config.set("community-service." + path + ".z", z);
  }
}
