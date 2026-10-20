package cloud.mineplay.punishments.communityservice;

/**
 * Eligibility and final event outcome are evaluated separately; MONITOR is not a final ordering
 * guarantee.
 */
public final class MiningRules {
  private MiningRules() {}

  public static boolean eligible(
      boolean active,
      boolean survival,
      boolean inside,
      boolean approvedBlock,
      boolean taggedTool,
      boolean correctTool,
      boolean reserved) {
    return active && survival && inside && approvedBlock && taggedTool && correctTool && !reserved;
  }

  public static boolean finalised(
      boolean cancelled, boolean nowAir, boolean stillActive, boolean sameSession) {
    return !cancelled && nowAir && stillActive && sameSession;
  }
}
