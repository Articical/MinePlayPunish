package cloud.mineplay.punishments.communityservice;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class PrisonProtectionConfigTest {
  private YamlConfiguration config;
  private World world;
  private World other;
  private MockedStatic<Bukkit> bukkit;

  @BeforeEach
  void setup() {
    config = new YamlConfiguration();
    config.set("community-service.enabled", true);
    world = world("world");
    other = world("other");
    bukkit = mockStatic(Bukkit.class);
    bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
    bukkit.when(() -> Bukkit.getWorld("other")).thenReturn(other);
  }

  @AfterEach
  void close() {
    bukkit.close();
  }

  private static World world(String name) {
    World result = mock(World.class);
    when(result.getUID()).thenReturn(UUID.randomUUID());
    when(result.getName()).thenReturn(name);
    when(result.getMinHeight()).thenReturn(-64);
    when(result.getMaxHeight()).thenReturn(320);
    return result;
  }

  private void region(String name, String worldName, int[] first, int[] second) {
    String root = "community-service." + name;
    config.set(root + ".world", worldName);
    for (int index = 0; index < 3; index++) {
      String coordinate = List.of("x", "y", "z").get(index);
      config.set(root + ".pos1." + coordinate, first[index]);
      config.set(root + ".pos2." + coordinate, second[index]);
    }
  }

  private void quarry() {
    region("quarry", "world", new int[] {10, 60, 10}, new int[] {12, 63, 12});
  }

  @Test
  void absentOrDisabledServiceProducesNoGuards() {
    assertTrue(PrisonProtection.read(new YamlConfiguration()).isEmpty());
    quarry();
    config.set("community-service.enabled", false);
    assertTrue(PrisonProtection.read(config).isEmpty());
  }

  @Test
  void legacyQuarryGetsFourBlocksOfHeadroomWithoutRequiringAdmissionLocations() {
    quarry();
    config.createSection("community-service.prison");
    var guards = PrisonProtection.read(config);
    assertEquals(
        List.of(new PrisonProtection(world.getUID(), new QuarryBounds(10, 60, 10, 12, 67, 12))),
        guards);
    assertTrue(guards.getFirst().contains(new Location(world, 10.5, 67.9, 10.5)));
    assertFalse(guards.getFirst().contains(new Location(world, 10.5, 68, 10.5)));
    assertThrows(UnsupportedOperationException.class, () -> guards.clear());
  }

  @Test
  void legacyHeadroomStopsAtTheWorldHeightLimit() {
    region("quarry", "world", new int[] {0, 318, 0}, new int[] {1, 319, 1});
    var guard = PrisonProtection.read(config).getFirst();
    assertEquals(319, guard.bounds().maxY());
    assertFalse(guard.contains(new Location(world, 0, 320, 0)));
  }

  @Test
  void explicitPrisonAndQuarryRemainGuardedIndependentlyEvenWhenLayoutIsInvalid() {
    region("prison", "other", new int[] {100, 50, 100}, new int[] {110, 80, 110});
    quarry();
    var guards = PrisonProtection.read(config);
    assertEquals(2, guards.size());
    assertEquals(other.getUID(), guards.get(0).world());
    assertEquals(80, guards.get(0).bounds().maxY());
    assertEquals(world.getUID(), guards.get(1).world());
    assertEquals(63, guards.get(1).bounds().maxY());
    assertTrue(guards.get(0).contains(new Location(other, 105, 70, 105)));
    assertTrue(guards.get(1).contains(new Location(world, 11, 62, 11)));
  }

  @Test
  void oversizedAreasAreProtectedWithoutTerrainOrChunkWork() {
    region("prison", "world", new int[] {-1000, -64, -1000}, new int[] {1000, 319, 1000});
    region("quarry", "world", new int[] {-999, 0, -999}, new int[] {999, 100, 999});
    assertEquals(2, PrisonProtection.read(config).size());
    verify(world, never()).getBlockAt(anyInt(), anyInt(), anyInt());
    verify(world, never()).isChunkLoaded(anyInt(), anyInt());
    verify(world, never()).getChunkAt(anyInt(), anyInt());
  }

  @Test
  void malformedOrPartialPrisonDoesNotRemoveACompleteQuarryGuardOrAddLegacyHeadroom() {
    quarry();
    for (Object value : List.of("broken", 42)) {
      config.set("community-service.prison", value);
      var guards = PrisonProtection.read(config);
      assertEquals(1, guards.size());
      assertEquals(63, guards.getFirst().bounds().maxY());
    }
    config.set("community-service.prison", null);
    config.set("community-service.prison.pos1.x", 1);
    assertEquals(63, PrisonProtection.read(config).getFirst().bounds().maxY());
  }

  @Test
  void incompleteQuarryDoesNotRemoveACompletePrisonGuard() {
    region("prison", "world", new int[] {0, 50, 0}, new int[] {20, 90, 20});
    quarry();
    config.set("community-service.quarry.pos2", null);
    assertEquals(
        List.of(new PrisonProtection(world.getUID(), new QuarryBounds(0, 50, 0, 20, 90, 20))),
        PrisonProtection.read(config));
  }

  @Test
  void invalidCoordinatesAndUnloadedWorldsAreSkipped() {
    quarry();
    String first = "community-service.quarry.pos1.";
    for (Object value : List.of("10", 10.5, 30_000_001, -30_000_001)) {
      config.set(first + "x", value);
      assertTrue(PrisonProtection.read(config).isEmpty());
    }
    config.set(first + "x", 10);
    for (int y : new int[] {-65, 320}) {
      config.set(first + "y", y);
      assertTrue(PrisonProtection.read(config).isEmpty());
    }
    config.set(first + "y", 60);
    config.set("community-service.quarry.world", "missing");
    assertTrue(PrisonProtection.read(config).isEmpty());
  }

  @Test
  void cornerWorldTagsMustMatchTheResolvedRegionWorld() {
    quarry();
    config.set("community-service.quarry.pos1.world", "other");
    assertTrue(PrisonProtection.read(config).isEmpty());
    config.set("community-service.quarry.pos1.world", "world");
    assertEquals(1, PrisonProtection.read(config).size());
  }

  @Test
  void prisonWorldDefaultsToQuarryWorldAndReversedCornersAreNormalised() {
    quarry();
    region("prison", null, new int[] {20, 90, 20}, new int[] {0, 50, 0});
    var guards = PrisonProtection.read(config);
    assertEquals(
        new PrisonProtection(world.getUID(), new QuarryBounds(0, 50, 0, 20, 90, 20)),
        guards.getFirst());
  }

  @Test
  void containmentUsesWorldIdentityAndInclusiveBlockCoordinates() {
    var guard = new PrisonProtection(world.getUID(), new QuarryBounds(-2, 60, -2, 2, 70, 2));
    assertTrue(guard.contains(new Location(world, -1.9, 60, 2.99)));
    assertFalse(guard.contains(new Location(world, -2.01, 60, 0)));
    assertFalse(guard.contains(new Location(world, 3, 60, 0)));
    assertFalse(guard.contains(new Location(other, 0, 60, 0)));
    assertFalse(guard.contains(new Location(world, Double.NaN, 60, 0)));
    assertFalse(guard.contains(new Location(null, 0, 60, 0)));
    assertFalse(guard.contains(null));
  }
}
