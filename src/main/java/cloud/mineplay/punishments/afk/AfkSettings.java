package cloud.mineplay.punishments.afk;

import org.bukkit.configuration.ConfigurationSection;

/** Validated settings copied before the server-thread idle detector runs. */
public record AfkSettings(
    boolean enabled, int idleTimeoutSeconds, int warningSeconds, int checkIntervalSeconds) {
  public AfkSettings {
    if (idleTimeoutSeconds < 60 || idleTimeoutSeconds > 86_400)
      throw new IllegalArgumentException(
          "anti-afk.idle-timeout-seconds must be between 60 and 86400");
    if (warningSeconds < 5 || warningSeconds >= idleTimeoutSeconds)
      throw new IllegalArgumentException(
          "anti-afk.warning-seconds must be between 5 and the idle timeout minus 1");
    if (checkIntervalSeconds < 1
        || checkIntervalSeconds > 30
        || checkIntervalSeconds > warningSeconds)
      throw new IllegalArgumentException(
          "anti-afk.check-interval-seconds must be between 1 and 30 and no greater than the warning"
              + " period");
  }

  public static AfkSettings read(ConfigurationSection config) {
    if (config.isSet("anti-afk.enabled") && !config.isBoolean("anti-afk.enabled"))
      throw new IllegalArgumentException("anti-afk.enabled must be a boolean");
    return new AfkSettings(
        config.getBoolean("anti-afk.enabled", true),
        integer(config, "anti-afk.idle-timeout-seconds", 900),
        integer(config, "anti-afk.warning-seconds", 120),
        integer(config, "anti-afk.check-interval-seconds", 5));
  }

  private static int integer(ConfigurationSection config, String key, int fallback) {
    if (!config.isSet(key)) return fallback;
    if (!config.isInt(key)) throw new IllegalArgumentException(key + " must be an integer");
    return config.getInt(key);
  }
}
