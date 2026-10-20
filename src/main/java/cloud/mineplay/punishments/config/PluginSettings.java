package cloud.mineplay.punishments.config;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import org.bukkit.configuration.file.FileConfiguration;

/** Validates the operational settings before any task or command is enabled. */
public record PluginSettings(
    String serverId,
    Path database,
    int minimumBlocks,
    int maximumBlocks,
    int maximumReasonLength,
    List<Integer> ranks,
    int protectedRank) {
  public static PluginSettings read(FileConfiguration c, Path directory) {
    String id = c.getString("server.id", "");
    require(
        id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"),
        "server.id must be a stable server identifier");
    require("SQLITE".equals(c.getString("database.type")), "Only SQLite is supported");
    Path root = directory.toAbsolutePath().normalize();
    Path file = root.resolve(c.getString("database.file", "punishments.db")).normalize();
    require(
        file.startsWith(root) && !file.equals(root),
        "database.file must stay inside the plugin directory");
    require(
        !c.getBoolean("punishments.escalation.enabled"),
        "Automatic escalation is not supported; leave disabled");
    for (String key :
        List.of(
            "database.wal-enabled",
            "sync.durable-outbox",
            "sync.durable-inbox",
            "punishments.bans.check-on-login",
            "punishments.bans.kick-online-targets",
            "punishments.warnings.notify-on-next-login",
            "community-service.equipment.prevent-durability-loss",
            "logging.audit-actions",
            "sync.reconcile-on-startup",
            "community-service.mining.suppress-drops",
            "community-service.mining.suppress-xp",
            "community-service.regeneration.persistent-queue",
            "community-service.regeneration.enabled",
            "community-service.restrictions.prevent-teleport-escape",
            "community-service.restrictions.prevent-item-transfer",
            "community-service.restrictions.prevent-block-placement",
            "community-service.restrictions.prevent-pvp",
            "community-service.restrictions.prevent-external-damage")) {
      require(c.getBoolean(key), key + " must remain true for safe operation");
    }
    require(
        "SNAPSHOT_AND_REPLACE".equals(c.getString("community-service.equipment.mode")),
        "Unsupported inventory mode");
    require(
        !c.getBoolean("community-service.equipment.keep-prison-items"),
        "Prison items cannot be retained");
    require(
        "REQUIRE_RECONCILIATION".equals(c.getString("sync.conflict-policy")),
        "Unsafe conflict policy");
    require(
        "PENDING_STAFF_RECOVERY".equals(c.getString("appeals.local-token-provisioning-fallback")),
        "Unsupported appeal fallback");
    URI base = https(c.getString("website.base-url"), "website.base-url");
    URI api = https(c.getString("website.api-url"), "website.api-url");
    URI appeals = https(c.getString("appeals.url-base"), "appeals.url-base");
    new cloud.mineplay.punishments.appeals.AppealLinks(appeals.toString());
    require(
        origin(base).equals(origin(api)) && origin(base).equals(origin(appeals)),
        "Website/API/appeal origins must match");
    require(
        c.getString("website.credential-env", "").matches("[A-Za-z_][A-Za-z0-9_]{0,127}"),
        "Invalid credential environment variable name");
    int min = bounded(c, "community-service.minimum-blocks", 1, 1_000_000);
    int max = bounded(c, "community-service.maximum-blocks", min, 1_000_000);
    bounded(c, "community-service.default-required-blocks", min, max);
    int reason = bounded(c, "punishments.maximum-reason-length", 1, 4000);
    bounded(c, "community-service.maximum-prisoners", 1, 1000);
    bounded(c, "community-service.regeneration.delay-ticks", 1, 72000);
    bounded(c, "community-service.regeneration.max-blocks-per-tick", 1, 1000);
    bounded(c, "community-service.progress.milestone-interval", 1, 1000000);
    bounded(c, "sync.batch-size", 1, 1000);
    bounded(c, "website.sync-interval-seconds", 1, 3600);
    bounded(c, "website.connection-timeout-seconds", 1, 120);
    bounded(c, "website.request-timeout-seconds", 1, 120);
    int retry = bounded(c, "website.retry-backoff-seconds", 1, 3600);
    bounded(c, "website.max-retry-backoff-seconds", retry, 86400);
    bounded(c, "punishments.kicks.remote-command-expiry-seconds", 1, 300);
    bounded(c, "punishments.warnings.expiration-days", 0, 36500);
    for (String label : c.getStringList("punishments.mutes.blocked-commands")) {
      require(label.matches("[A-Za-z0-9_-]+"), "Muted command entries must be bare labels");
    }
    List<Integer> ranks = c.getIntegerList("permissions.ranks");
    require(
        !ranks.isEmpty() && ranks.stream().allMatch(n -> n > 0 && n < 10000),
        "Ranks must be between 1 and 9999");
    return new PluginSettings(
        id,
        file,
        min,
        max,
        reason,
        List.copyOf(ranks),
        bounded(c, "permissions.protected-rank", 10000, 1000000));
  }

  private static int bounded(FileConfiguration c, String key, int min, int max) {
    require(c.isInt(key) || c.isLong(key), key + " must be an integer");
    long value = c.getLong(key);
    require(value >= min && value <= max, key + " must be between " + min + " and " + max);
    return (int) value;
  }

  public static URI https(String value, String name) {
    try {
      URI uri = URI.create(value == null ? "" : value);
      require(
          "https".equalsIgnoreCase(uri.getScheme())
              && uri.getHost() != null
              && uri.getUserInfo() == null
              && uri.getQuery() == null
              && uri.getFragment() == null,
          name + " must be an HTTPS URL without credentials/query/fragment");
      return uri;
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("Invalid " + name);
    }
  }

  private static String origin(URI uri) {
    return uri.getHost().toLowerCase(java.util.Locale.ROOT)
        + ":"
        + (uri.getPort() < 0 ? 443 : uri.getPort());
  }

  private static void require(boolean valid, String message) {
    if (!valid) throw new IllegalArgumentException(message);
  }
}
