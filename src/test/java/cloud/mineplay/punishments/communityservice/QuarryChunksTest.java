package cloud.mineplay.punishments.communityservice;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

class QuarryChunksTest {
  private record ChunkPosition(int x, int z) {}

  private record TestWorld(
      World world, UUID id, Set<ChunkPosition> loaded, Set<ChunkPosition> tickets) {}

  private MockedStatic<Bukkit> bukkit;
  private Plugin plugin;
  private TestWorld quarry;
  private TestWorld release;
  private QuarryChunks lease;
  private final QuarryBounds oneChunk = new QuarryBounds(0, 60, 0, 15, 62, 15);

  @BeforeEach
  void setup() {
    bukkit = mockStatic(Bukkit.class);
    bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
    plugin = mock(Plugin.class);
    quarry = world("quarry");
    release = world("release");
    lease = new QuarryChunks(plugin);
  }

  @AfterEach
  void cleanup() {
    bukkit.close();
  }

  private TestWorld world(String name) {
    World world = mock(World.class);
    UUID id = UUID.randomUUID();
    Set<ChunkPosition> loaded = new HashSet<>();
    Set<ChunkPosition> tickets = new HashSet<>();
    when(world.getUID()).thenReturn(id);
    when(world.getName()).thenReturn(name);
    when(world.getMinHeight()).thenReturn(-64);
    when(world.getMaxHeight()).thenReturn(320);
    when(world.isChunkLoaded(anyInt(), anyInt()))
        .thenAnswer(i -> loaded.contains(new ChunkPosition(i.getArgument(0), i.getArgument(1))));
    when(world.isChunkGenerated(anyInt(), anyInt())).thenReturn(true);
    when(world.addPluginChunkTicket(anyInt(), anyInt(), eq(plugin)))
        .thenAnswer(
            i -> {
              ChunkPosition chunk = new ChunkPosition(i.getArgument(0), i.getArgument(1));
              loaded.add(chunk);
              return tickets.add(chunk);
            });
    when(world.removePluginChunkTicket(anyInt(), anyInt(), eq(plugin)))
        .thenAnswer(i -> tickets.remove(new ChunkPosition(i.getArgument(0), i.getArgument(1))));
    bukkit.when(() -> Bukkit.getWorld(id)).thenReturn(world);
    return new TestWorld(world, id, loaded, tickets);
  }

  @Test
  void loadsAndHoldsOnlyMiningFootprintAndDeduplicatedDestinations() {
    QuarryBounds mine = new QuarryBounds(0, 60, 0, 31, 62, 31);
    Location arrival = new Location(quarry.world(), 0.5, 65, 0.5);
    Location exit = new Location(quarry.world(), 32.5, 65, 0.5);
    Location fallback = new Location(release.world(), 0.5, 65, 0.5);

    lease.acquire(quarry.world(), mine, List.of(arrival, exit, fallback, exit.clone()), 6);

    assertTrue(lease.loaded());
    assertEquals(
        Set.of(
            new ChunkPosition(0, 0),
            new ChunkPosition(0, 1),
            new ChunkPosition(1, 0),
            new ChunkPosition(1, 1),
            new ChunkPosition(2, 0)),
        quarry.tickets());
    assertEquals(Set.of(new ChunkPosition(0, 0)), release.tickets());
    verify(quarry.world(), times(5)).addPluginChunkTicket(anyInt(), anyInt(), eq(plugin));
    verify(release.world()).addPluginChunkTicket(0, 0, plugin);
    verify(quarry.world(), never()).getBlockAt(anyInt(), anyInt(), anyInt());
    verifyNoManualForceLoading();

    lease.close();
    assertFalse(lease.loaded());
    assertTrue(quarry.tickets().isEmpty());
    assertTrue(release.tickets().isEmpty());
    // Releasing our hold leaves normal chunk lifetime decisions to the server.
    assertFalse(quarry.loaded().isEmpty());
    verifyNoManualForceLoading();
  }

  @Test
  void negativeCoordinatesUseFloorChunkBoundaries() {
    QuarryBounds mine = new QuarryBounds(-17, 60, -1, 16, 62, 0);
    lease.acquire(quarry.world(), mine, List.of(new Location(quarry.world(), -0.5, 65, -0.5)), 8);

    assertEquals(8, quarry.tickets().size());
    for (int x = -2; x <= 1; x++) {
      assertTrue(quarry.tickets().contains(new ChunkPosition(x, -1)));
      assertTrue(quarry.tickets().contains(new ChunkPosition(x, 0)));
    }
  }

  @Test
  void alreadyLoadedChunksStillReceiveTicketsToPreventIdleUnload() {
    quarry.loaded().add(new ChunkPosition(0, 0));

    lease.acquire(quarry.world(), oneChunk, List.of(), 1);

    assertTrue(lease.loaded());
    verify(quarry.world()).addPluginChunkTicket(0, 0, plugin);
    verify(quarry.world(), never()).isChunkGenerated(anyInt(), anyInt());
  }

  @Test
  void preexistingTicketForThisPluginIsNeverRemovedByThisLease() {
    ChunkPosition existing = new ChunkPosition(0, 0);
    quarry.loaded().add(existing);
    quarry.tickets().add(existing);

    lease.acquire(quarry.world(), oneChunk, List.of(), 1);
    assertTrue(lease.loaded());
    lease.close();

    assertFalse(lease.loaded());
    assertEquals(Set.of(existing), quarry.tickets());
    verify(quarry.world(), never()).removePluginChunkTicket(anyInt(), anyInt(), any());
  }

  @Test
  void existingUnownedTicketsArePreservedDuringPartialFailure() {
    ChunkPosition existing = new ChunkPosition(0, 0);
    quarry.loaded().add(existing);
    quarry.tickets().add(existing);
    when(quarry.world().isChunkGenerated(1, 0)).thenReturn(false);

    assertThrows(
        IllegalArgumentException.class,
        () -> lease.acquire(quarry.world(), new QuarryBounds(0, 60, 0, 31, 62, 0), List.of(), 2));

    assertFalse(lease.loaded());
    assertEquals(Set.of(existing), quarry.tickets());
    verify(quarry.world(), never()).removePluginChunkTicket(anyInt(), anyInt(), any());
  }

  @Test
  void longThinQuarryCannotLoadThousandsOfChunksDespiteModestBlockVolume() {
    QuarryBounds strip = new QuarryBounds(0, 60, 0, 49_999, 60, 0);
    assertEquals(50_000, strip.volume());

    assertThrows(
        IllegalArgumentException.class, () -> lease.acquire(quarry.world(), strip, List.of(), 256));

    verifyNoChunkAccess();
    assertFalse(lease.loaded());
  }

  @Test
  void hugeFootprintIsCountedUsingLongBeforeEnumerationOrLoading() {
    QuarryBounds huge = new QuarryBounds(-30_000_000, 60, -30_000_000, 30_000_000, 60, 30_000_000);

    assertThrows(
        IllegalArgumentException.class, () -> lease.acquire(quarry.world(), huge, List.of(), 1024));

    verifyNoChunkAccess();
  }

  @Test
  void destinationsCountTowardCapBeforeAnyChunksAreLoaded() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            lease.acquire(
                quarry.world(), oneChunk, List.of(new Location(release.world(), 0.5, 65, 0.5)), 1));

    verifyNoChunkAccess();
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, 0, 1025, Integer.MAX_VALUE})
  void invalidMaximumCannotExpandChunkBudget(int maximum) {
    assertThrows(
        IllegalArgumentException.class,
        () -> lease.acquire(quarry.world(), oneChunk, List.of(), maximum));
    verifyNoChunkAccess();
  }

  @ParameterizedTest
  @ValueSource(
      doubles = {
        Double.NaN,
        Double.POSITIVE_INFINITY,
        Double.NEGATIVE_INFINITY,
        30_000_001,
        -30_000_001,
        Double.MAX_VALUE
      })
  void invalidDestinationHorizontalCoordinatesFailBeforeAnyLoad(double coordinate) {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            lease.acquire(
                quarry.world(),
                oneChunk,
                List.of(new Location(release.world(), coordinate, 65, 0)),
                2));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            lease.acquire(
                quarry.world(),
                oneChunk,
                List.of(new Location(release.world(), 0, 65, coordinate)),
                2));
    verifyNoChunkAccess();
  }

  @ParameterizedTest
  @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, -65, -64, -63.5, 319, 320})
  void unsafeDestinationHeightFailsBeforeLoading(double y) {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            lease.acquire(
                quarry.world(), oneChunk, List.of(new Location(release.world(), 0, y, 0)), 2));
    verifyNoChunkAccess();
  }

  @Test
  void invalidQuarryBoundsFailBeforeLoading() {
    List<QuarryBounds> invalid =
        List.of(
            new QuarryBounds(-30_000_001, 60, 0, 0, 62, 0),
            new QuarryBounds(0, 60, 0, 30_000_001, 62, 0),
            new QuarryBounds(0, 60, -30_000_001, 0, 62, 0),
            new QuarryBounds(0, 60, 0, 0, 62, 30_000_001),
            new QuarryBounds(0, -65, 0, 0, 62, 0),
            new QuarryBounds(0, 60, 0, 0, 320, 0));
    for (QuarryBounds bounds : invalid)
      assertThrows(
          IllegalArgumentException.class,
          () -> lease.acquire(quarry.world(), bounds, List.of(), 1));
    verifyNoChunkAccess();
  }

  @Test
  void missingAndStaleWorldsAreRejectedWithoutCreatingWorlds() {
    assertThrows(IllegalArgumentException.class, () -> lease.acquire(null, oneChunk, List.of(), 1));
    bukkit.when(() -> Bukkit.getWorld(quarry.id())).thenReturn(null);
    assertThrows(
        IllegalArgumentException.class,
        () -> lease.acquire(quarry.world(), oneChunk, List.of(), 1));
    bukkit.when(() -> Bukkit.getWorld(quarry.id())).thenReturn(quarry.world());
    bukkit.when(() -> Bukkit.getWorld(release.id())).thenReturn(null);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            lease.acquire(
                quarry.world(), oneChunk, List.of(new Location(release.world(), 0, 65, 0)), 2));
    verifyNoChunkAccess();
  }

  @Test
  void nullGeometryAndDestinationsFailBeforeAnyChunkLoads() {
    assertThrows(
        IllegalArgumentException.class, () -> lease.acquire(quarry.world(), null, List.of(), 1));
    assertThrows(
        IllegalArgumentException.class, () -> lease.acquire(quarry.world(), oneChunk, null, 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> lease.acquire(quarry.world(), oneChunk, Arrays.asList((Location) null), 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> lease.acquire(quarry.world(), oneChunk, List.of(new Location(null, 0, 65, 0)), 1));
    verifyNoChunkAccess();
  }

  @Test
  void missingGeneratedChunkDoesNotGenerateTerrainAndRollsBackPriorTickets() {
    when(quarry.world().isChunkGenerated(1, 0)).thenReturn(false);

    IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                lease.acquire(quarry.world(), new QuarryBounds(0, 60, 0, 31, 62, 0), List.of(), 2));

    assertTrue(failure.getMessage().contains("not been generated"));
    verify(quarry.world()).removePluginChunkTicket(0, 0, plugin);
    verify(quarry.world(), never()).addPluginChunkTicket(1, 0, plugin);
    assertTrue(quarry.tickets().isEmpty());
    assertFalse(lease.loaded());
    verifyNoManualForceLoading();
  }

  @Test
  void ticketThatDoesNotLoadChunkIsReleasedBeforeFailureReturns() {
    doReturn(true).when(quarry.world()).addPluginChunkTicket(0, 0, plugin);

    assertThrows(
        IllegalArgumentException.class,
        () -> lease.acquire(quarry.world(), oneChunk, List.of(), 1));

    verify(quarry.world()).removePluginChunkTicket(0, 0, plugin);
    assertFalse(lease.loaded());
  }

  @Test
  void addingLaterTicketFailureRollsBackEarlierOwnedTickets() {
    IllegalStateException original = new IllegalStateException("Ticket load failed");
    doThrow(original).when(release.world()).addPluginChunkTicket(0, 0, plugin);

    RuntimeException failure =
        assertThrows(
            RuntimeException.class,
            () ->
                lease.acquire(
                    quarry.world(), oneChunk, List.of(new Location(release.world(), 0, 65, 0)), 2));

    assertSame(original, failure);
    verify(quarry.world()).removePluginChunkTicket(0, 0, plugin);
    assertTrue(quarry.tickets().isEmpty());
    assertFalse(lease.loaded());
  }

  @Test
  void rollbackKeepsOriginalFailureAndRetainsFailedRemovalForRetry() {
    IllegalStateException original = new IllegalStateException("Load failure");
    IllegalStateException cleanup = new IllegalStateException("Cleanup failure");
    doThrow(original).when(release.world()).addPluginChunkTicket(0, 0, plugin);
    doThrow(cleanup).doReturn(true).when(quarry.world()).removePluginChunkTicket(0, 0, plugin);

    RuntimeException failure =
        assertThrows(
            RuntimeException.class,
            () ->
                lease.acquire(
                    quarry.world(), oneChunk, List.of(new Location(release.world(), 0, 65, 0)), 2));

    assertSame(original, failure);
    assertArrayEquals(new Throwable[] {cleanup}, original.getSuppressed());
    assertFalse(lease.loaded());
    lease.close();
    verify(quarry.world(), times(2)).removePluginChunkTicket(0, 0, plugin);
    lease.close();
    verify(quarry.world(), times(2)).removePluginChunkTicket(0, 0, plugin);
  }

  @Test
  void closeAttemptsEveryTicketAndRetriesOnlyFailedRemovals() {
    lease.acquire(quarry.world(), new QuarryBounds(0, 60, 0, 31, 62, 0), List.of(), 2);
    IllegalStateException cleanup = new IllegalStateException("Cleanup failure");
    doThrow(cleanup).doReturn(false).when(quarry.world()).removePluginChunkTicket(0, 0, plugin);

    assertSame(cleanup, assertThrows(IllegalStateException.class, lease::close));
    verify(quarry.world()).removePluginChunkTicket(1, 0, plugin);
    assertFalse(lease.loaded());
    lease.close();

    verify(quarry.world(), times(2)).removePluginChunkTicket(0, 0, plugin);
    verify(quarry.world(), times(1)).removePluginChunkTicket(1, 0, plugin);
  }

  @Test
  void multipleCleanupFailuresArePreserved() {
    lease.acquire(quarry.world(), new QuarryBounds(0, 60, 0, 31, 62, 0), List.of(), 2);
    IllegalStateException first = new IllegalStateException("First removal");
    IllegalStateException second = new IllegalStateException("Second removal");
    doThrow(first).when(quarry.world()).removePluginChunkTicket(0, 0, plugin);
    doThrow(second).when(quarry.world()).removePluginChunkTicket(1, 0, plugin);

    assertSame(first, assertThrows(IllegalStateException.class, lease::close));
    assertArrayEquals(new Throwable[] {second}, first.getSuppressed());
  }

  @Test
  void missingChunkOrReplacedWorldInvalidatesReadinessWithoutMutatingTickets() {
    lease.acquire(quarry.world(), oneChunk, List.of(), 1);
    assertTrue(lease.loaded());
    quarry.loaded().clear();
    assertFalse(lease.loaded());
    quarry.loaded().add(new ChunkPosition(0, 0));
    World replacement = mock(World.class);
    bukkit.when(() -> Bukkit.getWorld(quarry.id())).thenReturn(replacement);
    assertFalse(lease.loaded());
    bukkit.when(() -> Bukkit.getWorld(quarry.id())).thenReturn(null);
    assertFalse(lease.loaded());
    verify(quarry.world(), never()).removePluginChunkTicket(anyInt(), anyInt(), any());
  }

  @Test
  void closeIsIdempotentAndAllowsLaterReuse() {
    assertFalse(lease.loaded());
    lease.close();
    lease.acquire(quarry.world(), oneChunk, List.of(), 1);
    assertThrows(
        IllegalStateException.class, () -> lease.acquire(quarry.world(), oneChunk, List.of(), 1));
    assertTrue(lease.loaded());
    lease.close();
    lease.close();
    verify(quarry.world(), times(1)).removePluginChunkTicket(0, 0, plugin);

    lease.acquire(quarry.world(), oneChunk, List.of(), 1);
    assertTrue(lease.loaded());
    verify(quarry.world(), times(2)).addPluginChunkTicket(0, 0, plugin);
  }

  @Test
  void offThreadAcquireAndNonemptyCleanupAreRefusedBeforeMutation() {
    bukkit.when(Bukkit::isPrimaryThread).thenReturn(false);
    assertThrows(
        IllegalStateException.class, () -> lease.acquire(quarry.world(), oneChunk, List.of(), 1));
    verifyNoChunkAccess();
    assertDoesNotThrow(lease::close);
    assertFalse(lease.loaded());

    bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
    lease.acquire(quarry.world(), oneChunk, List.of(), 1);
    bukkit.when(Bukkit::isPrimaryThread).thenReturn(false);
    assertThrows(IllegalStateException.class, lease::close);
    verify(quarry.world(), never()).removePluginChunkTicket(anyInt(), anyInt(), any());
    bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
    assertTrue(lease.loaded());
    lease.close();
  }

  private void verifyNoChunkAccess() {
    for (TestWorld world : List.of(quarry, release)) {
      verify(world.world(), never()).isChunkLoaded(anyInt(), anyInt());
      verify(world.world(), never()).isChunkGenerated(anyInt(), anyInt());
      verify(world.world(), never()).addPluginChunkTicket(anyInt(), anyInt(), any());
      verify(world.world(), never()).removePluginChunkTicket(anyInt(), anyInt(), any());
    }
  }

  private void verifyNoManualForceLoading() {
    for (TestWorld world : List.of(quarry, release)) {
      verify(world.world(), never()).loadChunk(anyInt(), anyInt(), anyBoolean());
      verify(world.world(), never()).setChunkForceLoaded(anyInt(), anyInt(), anyBoolean());
      verify(world.world(), never()).unloadChunk(anyInt(), anyInt());
      verify(world.world(), never()).getChunkAt(anyInt(), anyInt());
    }
  }
}
