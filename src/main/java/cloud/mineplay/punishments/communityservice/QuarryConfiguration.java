package cloud.mineplay.punishments.communityservice;

import java.util.*;
import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;

/** Resolved only on the server thread. Configuration cannot weaken mandatory custody safeguards. */
record QuarryConfiguration(
    World world,
    QuarryBounds bounds,
    QuarryBounds prisonBounds,
    boolean explicitPrison,
    Location arrival,
    Location release,
    Location fallback,
    Set<Material> blocks,
    Set<Material> tools,
    Material regenerated,
    int delayTicks,
    int regenerationLimit,
    int maximumPrisoners,
    int milestoneInterval,
    boolean actionBar,
    boolean bossBar,
    List<String> safeCommands) {
  /** Compatibility constructor for existing integrations using the quarry's legacy custody area. */
  QuarryConfiguration(
      World world,
      QuarryBounds bounds,
      Location arrival,
      Location release,
      Location fallback,
      Set<Material> blocks,
      Set<Material> tools,
      Material regenerated,
      int delayTicks,
      int regenerationLimit,
      int maximumPrisoners,
      int milestoneInterval,
      boolean actionBar,
      boolean bossBar,
      List<String> safeCommands) {
    this(
        world,
        bounds,
        bounds,
        false,
        arrival,
        release,
        fallback,
        blocks,
        tools,
        regenerated,
        delayTicks,
        regenerationLimit,
        maximumPrisoners,
        milestoneInterval,
        actionBar,
        bossBar,
        safeCommands);
  }

  static QuarryConfiguration load(ConfigurationSection root) {
    ConfigurationSection c = root.getConfigurationSection("community-service");
    if (c == null || !c.getBoolean("enabled", true)) return null;
    if (!"SNAPSHOT_AND_REPLACE".equals(c.getString("equipment.mode", "SNAPSHOT_AND_REPLACE")))
      throw new IllegalArgumentException("Only SNAPSHOT_AND_REPLACE is supported");
    for (String key :
        List.of(
            "mining.suppress-drops",
            "mining.suppress-xp",
            "regeneration.enabled",
            "regeneration.persistent-queue",
            "restrictions.prevent-teleport-escape",
            "restrictions.prevent-item-transfer",
            "restrictions.prevent-block-placement",
            "restrictions.prevent-pvp",
            "restrictions.prevent-external-damage"))
      if (!c.getBoolean(key, true))
        throw new IllegalArgumentException(
            "Community Service safety option must remain enabled: " + key);
    if (c.getBoolean("equipment.keep-prison-items", false))
      throw new IllegalArgumentException("Prison tools cannot be retained");
    ConfigurationSection prison = c.getConfigurationSection("prison");
    if (c.isSet("prison") && prison == null)
      throw new IllegalArgumentException(
          "Prison configuration must contain world and corner fields");
    boolean explicitPrison =
        prison != null && (prison.isSet("world") || prison.isSet("pos1") || prison.isSet("pos2"));
    if (explicitPrison
        && (prison.getConfigurationSection("pos1") == null
            || prison.getConfigurationSection("pos2") == null))
      throw new IllegalArgumentException(
          "Explicit prison bounds require both pos1 and pos2; clear all prison fields to use legacy"
              + " quarry bounds");
    ConfigurationSection q = c.getConfigurationSection("quarry");
    if (q == null
        || q.getConfigurationSection("pos1") == null
        || q.getConfigurationSection("pos2") == null
        || q.getConfigurationSection("arrival") == null
        || q.getConfigurationSection("release") == null
        || q.getConfigurationSection("fallback") == null) return null;
    World world = Bukkit.getWorld(q.getString("world", "world"));
    if (world == null) throw new IllegalArgumentException("Quarry world is not loaded");
    ConfigurationSection a = q.getConfigurationSection("pos1"),
        b = q.getConfigurationSection("pos2");
    cornerWorld(a, world, "Quarry");
    cornerWorld(b, world, "Quarry");
    QuarryBounds bounds =
        QuarryBounds.between(
            coordinate(a, "x"),
            coordinate(a, "y"),
            coordinate(a, "z"),
            coordinate(b, "x"),
            coordinate(b, "y"),
            coordinate(b, "z"));
    validateBounds(bounds, world, "Quarry");
    long maximum = q.getLong("maximum-volume", 50000);
    if (maximum < 1 || maximum > 1_000_000 || bounds.volume() > maximum)
      throw new IllegalArgumentException("Quarry exceeds its maximum volume");
    if (!explicitPrison && bounds.maxY() >= world.getMaxHeight() - 3)
      throw new IllegalArgumentException("Quarry exceeds world height limits");
    QuarryBounds prisonBounds = bounds;
    if (explicitPrison) {
      World prisonWorld = Bukkit.getWorld(prison.getString("world", world.getName()));
      if (prisonWorld == null || !prisonWorld.getUID().equals(world.getUID()))
        throw new IllegalArgumentException("Prison and quarry must be in the same loaded world");
      ConfigurationSection first = prison.getConfigurationSection("pos1"),
          second = prison.getConfigurationSection("pos2");
      cornerWorld(first, world, "Prison");
      cornerWorld(second, world, "Prison");
      prisonBounds =
          QuarryBounds.between(
              coordinate(first, "x"),
              coordinate(first, "y"),
              coordinate(first, "z"),
              coordinate(second, "x"),
              coordinate(second, "y"),
              coordinate(second, "z"));
      validateBounds(prisonBounds, world, "Prison");
      if (!prisonBounds.contains(bounds))
        throw new IllegalArgumentException("Prison bounds must fully contain the mining quarry");
      // Only the mining quarry is volume-limited, chunk-checked and terrain-scanned below.
      // The outer prison may include large buildings, unvisited chunks and arbitrary materials.
    }
    for (int x = bounds.minX() >> 4; x <= bounds.maxX() >> 4; x++)
      for (int z = bounds.minZ() >> 4; z <= bounds.maxZ() >> 4; z++)
        if (!world.isChunkLoaded(x, z))
          throw new IllegalArgumentException("All quarry chunks must be loaded before validation");
    Set<Material> blocks =
        materials(
            c,
            "mining.allowed-blocks",
            Set.of(Material.STONE, Material.COBBLESTONE, Material.DEEPSLATE));
    // This allowlist deliberately excludes valuable ores, container blocks and dangerous materials.
    Set<Material> stone =
        Set.of(
            Material.STONE,
            Material.COBBLESTONE,
            Material.DEEPSLATE,
            Material.COBBLED_DEEPSLATE,
            Material.ANDESITE,
            Material.DIORITE,
            Material.GRANITE,
            Material.TUFF);
    if (blocks.isEmpty() || !stone.containsAll(blocks))
      throw new IllegalArgumentException("Allowed quarry blocks must be ordinary stone materials");
    Set<Material> tools =
        materials(
            c,
            "equipment.allowed-pickaxes",
            Set.of(Material.WOODEN_PICKAXE, Material.STONE_PICKAXE));
    if (tools.isEmpty()
        || !Set.of(Material.WOODEN_PICKAXE, Material.STONE_PICKAXE).containsAll(tools))
      throw new IllegalArgumentException("Only wooden and stone pickaxes are permitted");
    Material regenerated = material(c.getString("mining.regenerated-block", "STONE"));
    if (!blocks.contains(regenerated))
      throw new IllegalArgumentException("Regenerated material must be an allowed block");
    Location arrival = location(q.getConfigurationSection("arrival"), world),
        release = location(q.getConfigurationSection("release"), world),
        fallback = location(q.getConfigurationSection("fallback"), world);
    if (!inside(world, prisonBounds, explicitPrison, arrival))
      throw new IllegalArgumentException(
          explicitPrison
              ? "Arrival must be inside the explicit prison bounds"
              : "Arrival must be inside the quarry footprint and up to four blocks above it");
    if (inside(world, prisonBounds, explicitPrison, release)
        || inside(world, prisonBounds, explicitPrison, fallback))
      throw new IllegalArgumentException("Release and fallback must be outside the prison bounds");
    for (Location l : List.of(arrival, release, fallback))
      if (!safe(l))
        throw new IllegalArgumentException(
            "Arrival, release and fallback must have loaded chunks, solid floors and clear"
                + " headroom");
    int nonempty = 0;
    for (int x = bounds.minX(); x <= bounds.maxX(); x++)
      for (int y = bounds.minY(); y <= bounds.maxY(); y++)
        for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
          Material material = world.getBlockAt(x, y, z).getType();
          if (blocks.contains(material)) nonempty++;
          else if (!material.isAir())
            throw new IllegalArgumentException(
                "Quarry contains non-stone infrastructure at " + x + "," + y + "," + z);
        }
    if (nonempty == 0) throw new IllegalArgumentException("Quarry must contain approved stone");
    List<String> safe =
        c.getStringList("restrictions.allowed-commands").stream()
            .map(s -> s.toLowerCase(Locale.ROOT).trim())
            .toList();
    if (safe.isEmpty()) safe = List.of("service progress", "help", "rules");
    return new QuarryConfiguration(
        world,
        bounds,
        prisonBounds,
        explicitPrison,
        arrival,
        release,
        fallback,
        Set.copyOf(blocks),
        Set.copyOf(tools),
        regenerated,
        positive(c, "regeneration.delay-ticks", 60, 12000),
        positive(c, "regeneration.max-blocks-per-tick", 20, 1000),
        positive(c, "maximum-prisoners", 20, 1000),
        positive(c, "progress.milestone-interval", 50, 100000),
        c.getBoolean("progress.actionbar-enabled", true),
        c.getBoolean("progress.bossbar-enabled", true),
        safe);
  }

  boolean contains(Location l) {
    return sameWorld(world, l) && bounds.contains(l.getBlockX(), l.getBlockY(), l.getBlockZ());
  }

  boolean containsPrisoner(Location l) {
    return inside(world, prisonBounds, explicitPrison, l);
  }

  /** Structural and environmental protection covers the whole effective custody boundary. */
  boolean containsProtected(Location l) {
    return containsPrisoner(l);
  }

  Location safeRelease() {
    return safe(release) ? release.clone() : safe(fallback) ? fallback.clone() : null;
  }

  static boolean safe(Location l) {
    if (l == null
        || l.getWorld() == null
        || !Double.isFinite(l.getX())
        || !Double.isFinite(l.getY())
        || !Double.isFinite(l.getZ())
        || l.getBlockY() <= l.getWorld().getMinHeight()
        || l.getBlockY() + 1 >= l.getWorld().getMaxHeight()
        || !l.getWorld().isChunkLoaded(l.getBlockX() >> 4, l.getBlockZ() >> 4)) return false;
    Set<Material> danger =
        Set.of(
            Material.LAVA,
            Material.FIRE,
            Material.SOUL_FIRE,
            Material.MAGMA_BLOCK,
            Material.CAMPFIRE,
            Material.SOUL_CAMPFIRE,
            Material.CACTUS,
            Material.POWDER_SNOW,
            Material.SWEET_BERRY_BUSH,
            Material.WITHER_ROSE,
            Material.POINTED_DRIPSTONE);
    org.bukkit.block.Block feet = l.getBlock(),
        head = l.clone().add(0, 1, 0).getBlock(),
        floor = l.clone().add(0, -1, 0).getBlock();
    return feet.isPassable()
        && head.isPassable()
        && !feet.isLiquid()
        && !head.isLiquid()
        && floor.getType().isSolid()
        && !danger.contains(feet.getType())
        && !danger.contains(head.getType())
        && !danger.contains(floor.getType());
  }

  private static boolean inside(World w, QuarryBounds b, boolean explicit, Location l) {
    return sameWorld(w, l)
        && (explicit
            ? b.containsPoint(l.getX(), l.getY(), l.getZ())
            : b.containsPrisoner(l.getX(), l.getY(), l.getZ()));
  }

  private static boolean sameWorld(World world, Location location) {
    return location != null
        && location.getWorld() != null
        && location.getWorld().getUID().equals(world.getUID());
  }

  private static void validateBounds(QuarryBounds bounds, World world, String label) {
    if (bounds.minX() < -30_000_000
        || bounds.maxX() > 30_000_000
        || bounds.minZ() < -30_000_000
        || bounds.maxZ() > 30_000_000)
      throw new IllegalArgumentException(label + " exceeds Minecraft coordinate limits");
    if (bounds.minY() < world.getMinHeight() || bounds.maxY() >= world.getMaxHeight())
      throw new IllegalArgumentException(label + " exceeds world height limits");
  }

  private static void cornerWorld(ConfigurationSection corner, World world, String label) {
    if (!corner.isSet("world")) return;
    World configured = Bukkit.getWorld(corner.getString("world", ""));
    if (configured == null || !configured.getUID().equals(world.getUID()))
      throw new IllegalArgumentException(label + " corner must be in the same world as the quarry");
  }

  private static Location location(ConfigurationSection s, World fallback) {
    World w = Bukkit.getWorld(s.getString("world", fallback.getName()));
    if (w == null) throw new IllegalArgumentException("Location world not loaded");
    for (String key : List.of("x", "y", "z"))
      if (!s.isSet(key) || !Double.isFinite(s.getDouble(key)))
        throw new IllegalArgumentException("Location requires finite " + key);
    return new Location(
        w,
        s.getDouble("x"),
        s.getDouble("y"),
        s.getDouble("z"),
        (float) s.getDouble("yaw"),
        (float) s.getDouble("pitch"));
  }

  private static int coordinate(ConfigurationSection c, String name) {
    if (!c.isInt(name))
      throw new IllegalArgumentException("Quarry and prison corners need integer coordinates");
    return c.getInt(name);
  }

  private static Set<Material> materials(
      ConfigurationSection c, String key, Set<Material> fallback) {
    if (!c.isSet(key)) return fallback;
    Set<Material> values = new HashSet<>();
    for (String text : c.getStringList(key)) values.add(material(text));
    return values;
  }

  private static Material material(String name) {
    Material m = Material.matchMaterial(name);
    if (m == null) throw new IllegalArgumentException("Unknown material: " + name);
    return m;
  }

  private static int positive(ConfigurationSection c, String key, int fallback, int max) {
    int v = c.getInt(key, fallback);
    if (v < 1 || v > max) throw new IllegalArgumentException("Invalid community-service." + key);
    return v;
  }
}
