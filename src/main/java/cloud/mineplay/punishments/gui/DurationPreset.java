package cloud.mineplay.punishments.gui;

import java.time.Duration;

/** GUI labels deliberately distinguish a month from the command parser's minute suffix. */
public enum DurationPreset {
  HOUR("1 hour", Duration.ofHours(1)),
  FOUR_HOURS("4 hours", Duration.ofHours(4)),
  EIGHT_HOURS("8 hours", Duration.ofHours(8)),
  DAY("1 day", Duration.ofDays(1)),
  WEEK("1 week", Duration.ofDays(7)),
  MONTH("1 month (30 days)", Duration.ofDays(30)),
  PERMANENT("Permanent", null);

  private final String label;
  private final Duration duration;

  DurationPreset(String label, Duration duration) {
    this.label = label;
    this.duration = duration;
  }

  public String label() {
    return label;
  }

  public Duration duration() {
    return duration;
  }
}
