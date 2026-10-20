package cloud.mineplay.punishments.communityservice;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

class PrisonConfigurationTest {
  private YamlConfiguration config;
  private World world;
  private Block stone;
  private final List<String> scanned = new ArrayList<>();

  @BeforeEach
  void setup() {
    config = new YamlConfiguration();
    world = mock(World.class);
    stone = mock(Block.class);
    when(world.getUID()).thenReturn(UUID.randomUUID());
    when(world.getName()).thenReturn("world");
    when(world.getMinHeight()).thenReturn(-64);
    when(world.getMaxHeight()).thenReturn(320);
    when(world.isChunkLoaded(0, 0)).thenReturn(true);
    when(stone.getType()).thenReturn(Material.STONE);
    when(world.getBlockAt(anyInt(), anyInt(), anyInt()))
        .thenAnswer(
            call -> {
              scanned.add(
                  call.getArgument(0) + "," + call.getArgument(1) + "," + call.getArgument(2));
              return stone;
            });
    config.set("community-service.enabled", true);
    config.set("community-service.quarry.world", "world");
    point("quarry.pos1", 0, 64, 0);
    point("quarry.pos2", 1, 64, 1);
    point("quarry.arrival", 0.5, 65, 0.5);
    point("quarry.release", 20.5, 65, 20.5);
    point("quarry.fallback", 30.5, 65, 30.5);
  }

  private void point(String path, Number x, Number y, Number z) {
    config.set("community-service." + path + ".x", x);
    config.set("community-service." + path + ".y", y);
    config.set("community-service." + path + ".z", z);
  }

  private void explicitPrison() {
    point("prison.pos1", -10, 60, -10);
    point("prison.pos2", 15, 80, 15);
    config.set("community-service.prison.world", "world");
  }

  private QuarryConfiguration load() {
    // Location safety has independent tests. Here it is isolated so arbitrary prison terrain
    // can be distinguished from the only terrain validation allowed: mining quarry blocks.
    try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
        MockedStatic<QuarryConfiguration> locations =
            mockStatic(QuarryConfiguration.class, CALLS_REAL_METHODS)) {
      bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
      locations.when(() -> QuarryConfiguration.safe(any(Location.class))).thenReturn(true);
      return QuarryConfiguration.load(config);
    }
  }

  @Test
  void absentOrNullPrisonPreservesLegacyQuarryHeadroom() {
    QuarryConfiguration legacy = load();
    assertFalse(legacy.explicitPrison());
    assertEquals(legacy.bounds(), legacy.prisonBounds());
    assertTrue(legacy.containsPrisoner(new Location(world, 0.5, 68, 0.5)));
    assertTrue(legacy.containsProtected(new Location(world, 0.5, 68, 0.5)));
    assertFalse(legacy.containsPrisoner(new Location(world, 0.5, 68.01, 0.5)));
    config.set("community-service.prison.world", null);
    config.set("community-service.prison.pos1", null);
    config.set("community-service.prison.pos2", null);
    assertFalse(load().explicitPrison());
  }

  @Test
  void arrivalMayBeInsideBuildingOutsideMiningAreaAndPrisonHasNoExtraHeight() {
    explicitPrison();
    point("quarry.arrival", 10.5, 65, 10.5);
    QuarryConfiguration prison = load();
    assertTrue(prison.explicitPrison());
    assertFalse(prison.contains(prison.arrival()));
    assertTrue(prison.containsPrisoner(prison.arrival()));
    assertTrue(prison.containsProtected(prison.arrival()));
    assertTrue(prison.containsPrisoner(new Location(world, 15.99, 80.99, 15.99)));
    assertFalse(prison.containsPrisoner(new Location(world, 0, 81, 0)));
    assertFalse(prison.containsProtected(new Location(world, 16, 65, 0)));
  }

  @Test
  void hugeBuiltPrisonDoesNotUseMiningVolumeLimitOrScanItsTerrainOrChunks() {
    point("prison.pos1", -1000, -64, -1000);
    point("prison.pos2", 1000, 319, 1000);
    point("quarry.release", 2000.5, 65, 2000.5);
    point("quarry.fallback", 3000.5, 65, 3000.5);
    Block building = mock(Block.class);
    when(building.getType()).thenReturn(Material.OAK_PLANKS);
    when(world.getBlockAt(anyInt(), anyInt(), anyInt()))
        .thenAnswer(
            call -> {
              int x = call.getArgument(0), y = call.getArgument(1), z = call.getArgument(2);
              scanned.add(x + "," + y + "," + z);
              return x >= 0 && x <= 1 && y == 64 && z >= 0 && z <= 1 ? stone : building;
            });
    scanned.clear();
    QuarryConfiguration prison = load();
    assertTrue(prison.prisonBounds().volume() > 50_000);
    assertEquals(List.of("0,64,0", "0,64,1", "1,64,0", "1,64,1"), scanned);
    verify(world).isChunkLoaded(0, 0);
    verify(world, never()).isChunkLoaded(-63, -63);
  }

  @ParameterizedTest
  @ValueSource(strings = {"world", "pos1", "pos2"})
  void partialExplicitConfigurationCannotSilentlyFallBack(String field) {
    if (field.equals("world")) config.set("community-service.prison.world", "world");
    else point("prison." + field, 0, 64, 0);
    assertTrue(
        assertThrows(IllegalArgumentException.class, this::load)
            .getMessage()
            .contains("both pos1 and pos2"));
  }

  @Test
  void malformedPrisonSectionIsRejected() {
    config.set("community-service.prison", "not a section");
    assertThrows(IllegalArgumentException.class, this::load);
  }

  @Test
  void omittedPrisonWorldUsesQuarryWorldWhenBothCornersExist() {
    explicitPrison();
    config.set("community-service.prison.world", null);
    assertTrue(load().explicitPrison());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"prison.world", "prison.pos1.world", "prison.pos2.world", "quarry.pos1.world"})
  void wrongWorldCannotDefinePrisonOrQuarryCorners(String field) {
    explicitPrison();
    config.set("community-service." + field, "another_world");
    assertThrows(IllegalArgumentException.class, this::load);
  }

  @Test
  void prisonMustFullyContainMiningQuarry() {
    explicitPrison();
    point("prison.pos2", 0, 80, 15);
    assertTrue(
        assertThrows(IllegalArgumentException.class, this::load)
            .getMessage()
            .contains("fully contain"));
  }

  @ParameterizedTest
  @ValueSource(ints = {-65, 320})
  void prisonMustRespectWorldHeight(int invalidHeight) {
    explicitPrison();
    config.set(
        "community-service.prison." + (invalidHeight < 0 ? "pos1" : "pos2") + ".y", invalidHeight);
    assertTrue(
        assertThrows(IllegalArgumentException.class, this::load)
            .getMessage()
            .contains("height limits"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"release", "fallback"})
  void releaseLocationsMustBeOutsideWholePrison(String location) {
    explicitPrison();
    point("quarry." + location, 10.5, 65, 10.5);
    assertTrue(
        assertThrows(IllegalArgumentException.class, this::load)
            .getMessage()
            .contains("outside the prison"));
  }

  @Test
  void arrivalCannotUseLegacyHeadroomAboveExplicitPrison() {
    explicitPrison();
    point("quarry.arrival", 0.5, 81, 0.5);
    assertTrue(
        assertThrows(IllegalArgumentException.class, this::load)
            .getMessage()
            .contains("inside the explicit prison"));
  }

  @Test
  void miningVolumeAndLoadedChunkRequirementsStillApply() {
    explicitPrison();
    point("quarry.pos2", 100, 70, 100);
    assertTrue(
        assertThrows(IllegalArgumentException.class, this::load)
            .getMessage()
            .contains("maximum volume"));
    point("quarry.pos2", 1, 64, 1);
    when(world.isChunkLoaded(0, 0)).thenReturn(false);
    assertTrue(
        assertThrows(IllegalArgumentException.class, this::load)
            .getMessage()
            .contains("quarry chunks"));
  }

  @Test
  void miningAreaStillRejectsNonstoneInfrastructure() {
    explicitPrison();
    Material building = mock(Material.class);
    when(building.isAir()).thenReturn(false);
    when(stone.getType()).thenReturn(building);
    assertTrue(
        assertThrows(IllegalArgumentException.class, this::load)
            .getMessage()
            .contains("non-stone infrastructure"));
  }

  @Test
  void safetyRejectsLocationsWithoutEnoughWorldHeightOrFiniteCoordinates() {
    assertFalse(QuarryConfiguration.safe(new Location(world, 0, -64, 0)));
    assertFalse(QuarryConfiguration.safe(new Location(world, 0, 319, 0)));
    assertFalse(QuarryConfiguration.safe(new Location(world, Double.NaN, 64, 0)));
    verify(world, never()).getBlockAt(anyInt(), anyInt(), anyInt());
  }
}
