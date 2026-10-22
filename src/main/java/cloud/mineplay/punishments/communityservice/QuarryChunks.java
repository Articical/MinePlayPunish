package cloud.mineplay.punishments.communityservice;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

/** Owns only this lease's newly added plugin tickets, without persistent force-load flags. */
final class QuarryChunks implements AutoCloseable {
  private static final int MAXIMUM_CHUNKS = 1024;
  private static final int COORDINATE_LIMIT = 30_000_000;
  private final Plugin plugin;
  private final Set<RequiredChunk> owned = new LinkedHashSet<>();
  private Set<RequiredChunk> required = Set.of();
  private boolean acquired;

  private record RequiredChunk(World world, UUID worldId, int x, int z) {}

  QuarryChunks(Plugin plugin) {
    this.plugin = java.util.Objects.requireNonNull(plugin, "plugin");
  }

  /** Validates the complete bounded plan before loading anything. Close before reusing a lease. */
  void acquire(
      World quarryWorld, QuarryBounds bounds, List<Location> destinations, int maximumChunks) {
    requireMainThread();
    if (acquired || !required.isEmpty() || !owned.isEmpty())
      throw new IllegalStateException("Close the previous quarry chunk lease before acquiring");
    Set<RequiredChunk> plan = plan(quarryWorld, bounds, destinations, maximumChunks);
    required = plan;
    try {
      for (RequiredChunk chunk : plan) {
        World world = chunk.world();
        if (!world.isChunkLoaded(chunk.x(), chunk.z())
            && !world.isChunkGenerated(chunk.x(), chunk.z()))
          throw new IllegalArgumentException(
              "Quarry or destination chunk has not been generated: "
                  + world.getName()
                  + " "
                  + chunk.x()
                  + ","
                  + chunk.z());
        // addPluginChunkTicket loads the existing chunk and provides the only retention hold.
        // World.loadChunk would add a separate manual hold that ticket cleanup cannot release.
        if (world.addPluginChunkTicket(chunk.x(), chunk.z(), plugin)) owned.add(chunk);
        if (Bukkit.getWorld(chunk.worldId()) != world || !world.isChunkLoaded(chunk.x(), chunk.z()))
          throw new IllegalArgumentException(
              "Could not load quarry or destination chunk: "
                  + world.getName()
                  + " "
                  + chunk.x()
                  + ","
                  + chunk.z());
      }
      acquired = true;
    } catch (RuntimeException | Error failure) {
      try {
        close();
      } catch (RuntimeException | Error cleanupFailure) {
        if (cleanupFailure != failure) failure.addSuppressed(cleanupFailure);
      }
      throw failure;
    }
  }

  /** Called only on the server thread; a missing/replaced world invalidates the lease. */
  boolean loaded() {
    if (!acquired) return false;
    requireMainThread();
    for (RequiredChunk chunk : required)
      if (Bukkit.getWorld(chunk.worldId()) != chunk.world()
          || !chunk.world().isChunkLoaded(chunk.x(), chunk.z())) return false;
    return true;
  }

  /** Release failures remain owned so a later close can retry; attempt every owned ticket. */
  @Override
  public void close() {
    if (!owned.isEmpty()) requireMainThread();
    acquired = false;
    Throwable failure = null;
    for (RequiredChunk chunk : new ArrayList<>(owned)) {
      try {
        // false means the ticket is already absent, which also completes this lease's cleanup.
        chunk.world().removePluginChunkTicket(chunk.x(), chunk.z(), plugin);
        owned.remove(chunk);
      } catch (RuntimeException | Error cleanupFailure) {
        if (failure == null) failure = cleanupFailure;
        else if (failure != cleanupFailure) failure.addSuppressed(cleanupFailure);
      }
    }
    if (owned.isEmpty()) required = Set.of();
    if (failure instanceof RuntimeException runtime) throw runtime;
    if (failure instanceof Error error) throw error;
  }

  private static Set<RequiredChunk> plan(
      World quarryWorld, QuarryBounds bounds, List<Location> destinations, int maximumChunks) {
    if (maximumChunks < 1 || maximumChunks > MAXIMUM_CHUNKS)
      throw new IllegalArgumentException("Quarry chunk limit must be between 1 and 1024");
    UUID quarryId = validateWorld(quarryWorld);
    if (bounds == null
        || bounds.minX() < -COORDINATE_LIMIT
        || bounds.maxX() > COORDINATE_LIMIT
        || bounds.minZ() < -COORDINATE_LIMIT
        || bounds.maxZ() > COORDINATE_LIMIT
        || bounds.minY() < quarryWorld.getMinHeight()
        || bounds.maxY() >= quarryWorld.getMaxHeight())
      throw new IllegalArgumentException("Quarry bounds exceed world coordinate or height limits");
    if (destinations == null)
      throw new IllegalArgumentException("Quarry destinations must be supplied");
    int firstX = bounds.minX() >> 4, lastX = bounds.maxX() >> 4;
    int firstZ = bounds.minZ() >> 4, lastZ = bounds.maxZ() >> 4;
    long count = ((long) lastX - firstX + 1) * ((long) lastZ - firstZ + 1);
    if (count > maximumChunks)
      throw new IllegalArgumentException("Quarry footprint exceeds its chunk limit");
    Set<RequiredChunk> plan = new LinkedHashSet<>();
    for (int x = firstX; x <= lastX; x++)
      for (int z = firstZ; z <= lastZ; z++)
        plan.add(new RequiredChunk(quarryWorld, quarryId, x, z));
    for (Location destination : destinations) {
      if (destination == null)
        throw new IllegalArgumentException("Quarry destination must not be null");
      World world = destination.getWorld();
      UUID worldId = validateWorld(world);
      if (!Double.isFinite(destination.getX())
          || !Double.isFinite(destination.getY())
          || !Double.isFinite(destination.getZ())
          || Math.abs(destination.getX()) > COORDINATE_LIMIT
          || Math.abs(destination.getZ()) > COORDINATE_LIMIT
          || destination.getY() < (double) world.getMinHeight() + 1
          || destination.getY() >= (double) world.getMaxHeight() - 1)
        throw new IllegalArgumentException(
            "Quarry destination exceeds world coordinate or height limits");
      plan.add(
          new RequiredChunk(
              world, worldId, destination.getBlockX() >> 4, destination.getBlockZ() >> 4));
      if (plan.size() > maximumChunks)
        throw new IllegalArgumentException("Quarry and destinations exceed their chunk limit");
    }
    return java.util.Collections.unmodifiableSet(plan);
  }

  private static UUID validateWorld(World world) {
    if (world == null)
      throw new IllegalArgumentException("Quarry or destination world is not loaded");
    UUID id = world.getUID();
    if (id == null || Bukkit.getWorld(id) != world)
      throw new IllegalArgumentException("Quarry or destination world is not loaded");
    return id;
  }

  private static void requireMainThread() {
    if (!Bukkit.isPrimaryThread())
      throw new IllegalStateException("Quarry chunk tickets require the server thread");
  }
}
