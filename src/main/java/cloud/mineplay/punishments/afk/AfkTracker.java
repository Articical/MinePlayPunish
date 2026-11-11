package cloud.mineplay.punishments.afk;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * One connection's bounded, server-thread-confined idle state. This is a heuristic for ordinary AFK
 * machines, not proof of human presence against arbitrary client automation.
 */
public final class AfkTracker {
  public enum Outcome {
    NONE,
    WARN,
    KICK
  }

  public record Decision(Outcome outcome, String code, long remainingSeconds) {}

  private static final Decision NONE = new Decision(Outcome.NONE, null, 0);
  private static final Decision KICK = new Decision(Outcome.KICK, null, 0);
  private static final double CELL_SIZE = 4.0;
  private static final int POSITION_HISTORY = 256;
  private static final int ACTION_HISTORY = 256;
  private static final int CHAT_HISTORY = 32;
  private final LongSupplier nanoTime;
  private final Supplier<String> challengeCodes;
  private final History<Cell> positions = new History<>(POSITION_HISTORY);
  private final History<Block> actions = new History<>(ACTION_HISTORY);
  private final History<String> chats = new History<>(CHAT_HISTORY);
  private long lastActivity;
  private boolean hasPosition;
  private UUID previousWorld;
  private double previousX, previousZ;
  private String challenge;
  private long warningIssued, warningGrace;

  private record Cell(UUID world, long x, long z) {}

  private record Block(UUID world, int x, int y, int z) {}

  public AfkTracker(LongSupplier nanoTime, Supplier<String> challengeCodes) {
    this.nanoTime = Objects.requireNonNull(nanoTime);
    this.challengeCodes = Objects.requireNonNull(challengeCodes);
    lastActivity = nanoTime.getAsLong();
  }

  /**
   * Only movement with actual horizontal controls into a new horizontal cell counts. Physics-only
   * samples update the baseline, but cannot populate history or credit idle time. Yaw/pitch and Y
   * are intentionally absent so spinning and jumping never become evidence of activity.
   */
  public void sample(UUID world, double x, double z, boolean manualHorizontal) {
    if (world == null || !Double.isFinite(x) || !Double.isFinite(z)) {
      invalidatePosition();
      return;
    }
    boolean comparable = hasPosition && world.equals(previousWorld);
    Cell cell = cell(world, x, z);
    boolean changedCell = comparable && !cell.equals(cell(previousWorld, previousX, previousZ));
    previousWorld = world;
    previousX = x;
    previousZ = z;
    hasPosition = true;
    if (!manualHorizontal) return;
    // Seed a fresh baseline without rewarding a join, teleport or dimension change.
    if (!comparable) {
      positions.add(cell);
      return;
    }
    if (changedCell && positions.add(cell)) activity();
  }

  /** Called on teleport/respawn before observing the new location; history stays intact. */
  public void invalidatePosition() {
    hasPosition = false;
    previousWorld = null;
  }

  /**
   * Count successful building/mining at distinct coordinates, not break/place cycles in one spot.
   */
  public void action(UUID world, int x, int y, int z) {
    if (world != null && actions.add(new Block(world, x, y, z))) activity();
  }

  /** Receives only a bounded fingerprint of a delivered chat message, never the chat text. */
  public void chat(String fingerprint) {
    if (fingerprint != null
        && !fingerprint.isBlank()
        && fingerprint.length() <= 128
        && chats.add(fingerprint)) activity();
  }

  /**
   * A first late check warns rather than immediately kicking. An already-issued warning keeps its
   * full grace through a shorter timeout/reload. Real activity resets the timer and consumes the
   * challenge; novelty histories are deliberately retained instead of expiring every idle period.
   */
  public Decision evaluate(AfkSettings settings) {
    Objects.requireNonNull(settings);
    if (!settings.enabled()) {
      clearWarning();
      return NONE;
    }
    long now = nanoTime.getAsLong();
    long idle = elapsed(now, lastActivity);
    long timeout = nanos(settings.idleTimeoutSeconds());
    long threshold = timeout - nanos(settings.warningSeconds());
    if (challenge != null) return remainingNanos(now, settings) == 0 ? KICK : NONE;
    if (idle < threshold) {
      return NONE;
    }
    String code = challengeCodes.get();
    if (code == null || !code.matches("[A-Za-z0-9]{4,32}"))
      throw new IllegalStateException("Invalid AFK challenge generator result");
    challenge = code;
    warningIssued = now;
    warningGrace = nanos(settings.warningSeconds());
    return new Decision(Outcome.WARN, code, remaining(now, settings));
  }

  /**
   * Another plugin cancelled this idle kick: ask again after a fresh grace, without claiming
   * activity.
   */
  public void cancelledKick() {
    clearWarning();
  }

  /** Wrong, expired, replayed and unrelated-session replies do not alter the idle timer. */
  public boolean confirm(String code, AfkSettings settings) {
    Objects.requireNonNull(settings);
    if (!settings.enabled()
        || challenge == null
        || code == null
        || code.length() > 32
        || !challenge.equalsIgnoreCase(code)) return false;
    long now = nanoTime.getAsLong();
    if (remainingNanos(now, settings) == 0) return false;
    activity(now);
    return true;
  }

  private long remaining(long now, AfkSettings settings) {
    long remaining = remainingNanos(now, settings);
    return (remaining + TimeUnit.SECONDS.toNanos(1) - 1) / TimeUnit.SECONDS.toNanos(1);
  }

  private long remainingNanos(long now, AfkSettings settings) {
    long idleRemaining = nanos(settings.idleTimeoutSeconds()) - elapsed(now, lastActivity);
    long graceRemaining =
        Math.max(warningGrace, nanos(settings.warningSeconds())) - elapsed(now, warningIssued);
    return Math.max(0, Math.max(idleRemaining, graceRemaining));
  }

  private void activity() {
    activity(nanoTime.getAsLong());
  }

  private void activity(long now) {
    lastActivity = now;
    clearWarning();
  }

  private void clearWarning() {
    challenge = null;
    warningGrace = 0;
  }

  private static Cell cell(UUID world, double x, double z) {
    return new Cell(world, (long) Math.floor(x / CELL_SIZE), (long) Math.floor(z / CELL_SIZE));
  }

  private static long nanos(int seconds) {
    return TimeUnit.SECONDS.toNanos(seconds);
  }

  private static long elapsed(long now, long start) {
    // nanoTime is a relative clock; subtraction also works across signed long wraparound.
    return Math.max(0, now - start);
  }

  private static final class History<T> {
    private final int maximum;
    private final LinkedHashSet<T> entries = new LinkedHashSet<>();

    private History(int maximum) {
      this.maximum = maximum;
    }

    private boolean add(T value) {
      if (entries.contains(value)) return false;
      if (entries.size() == maximum) entries.removeFirst();
      entries.add(value);
      return true;
    }
  }
}
