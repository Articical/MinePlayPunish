package cloud.mineplay.punishments.communityservice;

/** Inclusive block cuboid, independent of Bukkit/world availability. */
public record QuarryBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
  public QuarryBounds {
    if (minX > maxX || minY > maxY || minZ > maxZ)
      throw new IllegalArgumentException("Inverted quarry bounds");
  }

  public static QuarryBounds between(int x1, int y1, int z1, int x2, int y2, int z2) {
    return new QuarryBounds(
        Math.min(x1, x2),
        Math.min(y1, y2),
        Math.min(z1, z2),
        Math.max(x1, x2),
        Math.max(y1, y2),
        Math.max(z1, z2));
  }

  public boolean contains(int x, int y, int z) {
    return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
  }

  /** Exact continuous space occupied by the inclusive block cuboid, without prisoner headroom. */
  public boolean containsPoint(double x, double y, double z) {
    return Double.isFinite(x)
        && Double.isFinite(y)
        && Double.isFinite(z)
        && x >= minX
        && x < (double) maxX + 1
        && y >= minY
        && y < (double) maxY + 1
        && z >= minZ
        && z < (double) maxZ + 1;
  }

  /** Full cuboid containment; checking only an overlapping corner is insufficient for custody. */
  public boolean contains(QuarryBounds other) {
    return other != null
        && contains(other.minX, other.minY, other.minZ)
        && contains(other.maxX, other.maxY, other.maxZ);
  }

  public boolean containsPrisoner(double x, double y, double z) {
    return x >= minX
        && x < (double) maxX + 1
        && z >= minZ
        && z < (double) maxZ + 1
        && y >= minY
        && y <= (double) maxY + 4;
  }

  public long volume() {
    return Math.multiplyExact(
        Math.multiplyExact((long) maxX - minX + 1, (long) maxY - minY + 1), (long) maxZ - minZ + 1);
  }
}
