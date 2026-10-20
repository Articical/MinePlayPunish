package cloud.mineplay.punishments.communityservice;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

/** Small immutable guards that remain available while the full admission layout is invalid. */
public record PrisonProtection(UUID world, QuarryBounds bounds) {
  public PrisonProtection {
    Objects.requireNonNull(world, "world");
    Objects.requireNonNull(bounds, "bounds");
  }

  public boolean contains(Location location) {
    return location != null
        && location.getWorld() != null
        && world.equals(location.getWorld().getUID())
        && Double.isFinite(location.getX())
        && Double.isFinite(location.getY())
        && Double.isFinite(location.getZ())
        && bounds.contains(location.getBlockX(), location.getBlockY(), location.getBlockZ());
  }

  /**
   * Reads only complete geometry; never loads chunks, scans terrain, or tests admission readiness.
   */
  public static List<PrisonProtection> read(ConfigurationSection root) {
    ConfigurationSection service = root.getConfigurationSection("community-service");
    if (service == null || !service.getBoolean("enabled", true)) return List.of();
    ConfigurationSection quarry = service.getConfigurationSection("quarry");
    ConfigurationSection prison = service.getConfigurationSection("prison");
    String quarryWorld = quarry == null ? "world" : quarry.getString("world", "world");
    boolean explicitPrison =
        (service.isSet("prison") && prison == null)
            || (prison != null
                && (prison.isSet("world") || prison.isSet("pos1") || prison.isSet("pos2")));
    List<PrisonProtection> guards = new ArrayList<>();
    PrisonProtection outer = readRegion(prison, quarryWorld, false);
    if (outer != null) guards.add(outer);
    PrisonProtection mine = readRegion(quarry, "world", !explicitPrison);
    if (mine != null) guards.add(mine);
    return List.copyOf(guards);
  }

  private static PrisonProtection readRegion(
      ConfigurationSection region, String defaultWorld, boolean legacyHeadroom) {
    if (region == null) return null;
    ConfigurationSection first = region.getConfigurationSection("pos1");
    ConfigurationSection second = region.getConfigurationSection("pos2");
    if (first == null || second == null) return null;
    String worldName = region.getString("world", defaultWorld);
    if (worldName == null || worldName.isBlank()) return null;
    World world = Bukkit.getWorld(worldName);
    if (world == null || !validCorner(first, world) || !validCorner(second, world)) return null;
    QuarryBounds bounds =
        QuarryBounds.between(
            first.getInt("x"),
            first.getInt("y"),
            first.getInt("z"),
            second.getInt("x"),
            second.getInt("y"),
            second.getInt("z"));
    if (legacyHeadroom) {
      int top = (int) Math.min((long) bounds.maxY() + 4, (long) world.getMaxHeight() - 1);
      bounds =
          new QuarryBounds(
              bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), top, bounds.maxZ());
    }
    return new PrisonProtection(world.getUID(), bounds);
  }

  private static boolean validCorner(ConfigurationSection corner, World world) {
    if (!corner.isInt("x") || !corner.isInt("y") || !corner.isInt("z")) return false;
    if (corner.isSet("world")) {
      String name = corner.getString("world");
      World tagged = name == null || name.isBlank() ? null : Bukkit.getWorld(name);
      if (tagged == null || !tagged.getUID().equals(world.getUID())) return false;
    }
    int x = corner.getInt("x"), y = corner.getInt("y"), z = corner.getInt("z");
    return x >= -30_000_000
        && x <= 30_000_000
        && z >= -30_000_000
        && z <= 30_000_000
        && y >= world.getMinHeight()
        && y < world.getMaxHeight();
  }
}
