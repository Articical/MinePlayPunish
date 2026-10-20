package cloud.mineplay.punishments.integration;

import java.net.URI;
import java.time.Duration;

public record SyncSettings(
    boolean enabled,
    String serverId,
    URI apiRoot,
    String credentialEnvironment,
    Duration connectTimeout,
    Duration requestTimeout,
    int intervalSeconds,
    int backoffSeconds,
    int maxBackoffSeconds,
    int batchSize,
    int kickExpirySeconds,
    boolean appealsEnabled,
    String appealBase) {
  public SyncSettings {
    ApiProtocol.httpsRoot(apiRoot.toString());
    if (serverId == null || !serverId.matches("[A-Za-z0-9_-]{1,64}"))
      throw new IllegalArgumentException("Invalid server ID");
    if (credentialEnvironment == null
        || !credentialEnvironment.matches("[A-Za-z_][A-Za-z0-9_]{0,127}"))
      throw new IllegalArgumentException("Invalid credential environment name");
    if (connectTimeout.isNegative()
        || connectTimeout.isZero()
        || connectTimeout.compareTo(Duration.ofSeconds(120)) > 0
        || requestTimeout.isNegative()
        || requestTimeout.isZero()
        || requestTimeout.compareTo(Duration.ofSeconds(120)) > 0
        || intervalSeconds < 1
        || intervalSeconds > 3600
        || backoffSeconds < 1
        || maxBackoffSeconds < backoffSeconds
        || maxBackoffSeconds > 86400
        || batchSize < 1
        || batchSize > 1000
        || kickExpirySeconds < 1
        || kickExpirySeconds > 300)
      throw new IllegalArgumentException("Invalid website timing or batch configuration");
    new cloud.mineplay.punishments.appeals.AppealLinks(appealBase);
  }

  public long backoff(int retry) {
    long multiplier = 1L << Math.min(Math.max(retry, 0), 30);
    return Math.min(maxBackoffSeconds, backoffSeconds * multiplier);
  }
}
