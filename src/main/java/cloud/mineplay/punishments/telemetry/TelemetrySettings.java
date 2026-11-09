package cloud.mineplay.punishments.telemetry;

import org.bukkit.configuration.ConfigurationSection;

/** Main-thread configuration copy used by collection and sync lifecycle. */
public record TelemetrySettings(boolean enabled, int refreshSeconds, int playersPerTick) {
  public TelemetrySettings {
    if (refreshSeconds < 10 || refreshSeconds > 60 || playersPerTick < 1 || playersPerTick > 100)
      throw new IllegalArgumentException("Invalid player telemetry collection settings");
  }

  public static TelemetrySettings read(ConfigurationSection config) {
    return new TelemetrySettings(
        config.getBoolean("website.enabled", true)
            && config.getBoolean("player-telemetry.enabled", true),
        integer(config, "player-telemetry.refresh-seconds", 30),
        integer(config, "player-telemetry.players-per-tick", 5));
  }

  private static int integer(ConfigurationSection config, String key, int fallback) {
    if (!config.isSet(key)) return fallback;
    if (!config.isInt(key)) throw new IllegalArgumentException(key + " must be an integer");
    return config.getInt(key);
  }
}
