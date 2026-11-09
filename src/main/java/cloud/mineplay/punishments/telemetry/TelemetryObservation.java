package cloud.mineplay.punishments.telemetry;

import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Immutable, validated observations. Contains no live server objects. */
public record TelemetryObservation(
    UUID playerId,
    String playerName,
    Instant observedAt,
    Instant firstJoinedAt,
    Instant lastJoinedAt,
    Instant lastLeftAt,
    boolean online,
    Long totalPlaySeconds,
    Long loginCount,
    Economy economy,
    List<Session> sessions,
    List<Address> recentAddresses) {
  public enum Status {
    AVAILABLE,
    UNAVAILABLE,
    NOT_INSTALLED,
    NO_ACCOUNT
  }

  public record Economy(Status status, String balance, String provider, String currency) {
    public Economy {
      Objects.requireNonNull(status, "status");
      if (status == Status.AVAILABLE) {
        if (balance == null || !balance.matches("-?(0|[1-9][0-9]{0,17})(\\.[0-9]{1,6})?"))
          throw invalid("economy.balance");
        text(provider, 128, true, "economy.provider");
        text(currency, 32, true, "economy.currency");
      } else if (balance != null) throw invalid("economy.balance");
      if (provider != null) text(provider, 128, true, "economy.provider");
      if (currency != null) text(currency, 32, true, "economy.currency");
    }

    /** Exact conversion only: unsupported precision and non-finite values remain unavailable. */
    public static Economy available(double balance, String provider, String currency) {
      try {
        if (!Double.isFinite(balance)) return unavailable();
        BigDecimal decimal = BigDecimal.valueOf(balance).stripTrailingZeros();
        if (decimal.scale() > 6 || decimal.precision() - decimal.scale() > 18) return unavailable();
        return new Economy(Status.AVAILABLE, decimal.toPlainString(), provider, currency);
      } catch (IllegalArgumentException failure) {
        return unavailable();
      }
    }

    public static Economy unavailable() {
      return new Economy(Status.UNAVAILABLE, null, null, null);
    }
  }

  public record Session(UUID sessionId, Instant joinedAt, Instant leftAt) {
    public Session {
      uuid(sessionId, "sessionId");
      joinedAt = time(joinedAt, "session.joinedAt");
      leftAt = leftAt == null ? null : time(leftAt, "session.leftAt");
      if (leftAt != null && leftAt.isBefore(joinedAt)) throw invalid("session.timeline");
    }
  }

  public record Address(String address, Instant lastSeenAt) {
    public Address {
      address = normalizeAddress(address);
      lastSeenAt = time(lastSeenAt, "address.lastSeenAt");
    }
  }

  public TelemetryObservation {
    uuid(playerId, "playerUuid");
    text(playerName, 64, true, "playerName");
    observedAt = time(observedAt, "observedAt");
    firstJoinedAt = time(firstJoinedAt, "firstJoinedAt");
    lastJoinedAt = time(lastJoinedAt, "lastJoinedAt");
    lastLeftAt = lastLeftAt == null ? null : time(lastLeftAt, "lastLeftAt");
    if (firstJoinedAt.isAfter(lastJoinedAt) || lastJoinedAt.isAfter(observedAt))
      throw invalid("join.timeline");
    if (lastLeftAt != null
        && (lastLeftAt.isBefore(firstJoinedAt)
            || lastLeftAt.isAfter(observedAt)
            || (online ? lastLeftAt.isAfter(lastJoinedAt) : lastLeftAt.isBefore(lastJoinedAt))))
      throw invalid("logout.timeline");
    counter(totalPlaySeconds, "totalPlaySeconds");
    counter(loginCount, "loginCount");
    Objects.requireNonNull(economy, "economy");
    sessions = List.copyOf(sessions);
    recentAddresses = List.copyOf(recentAddresses);
    if (sessions.size() > 1000 || recentAddresses.size() > 5) throw invalid("history.limit");
    HashSet<UUID> ids = new HashSet<>();
    for (Session session : sessions) {
      if (!ids.add(session.sessionId())
          || session.joinedAt().isBefore(firstJoinedAt)
          || session.joinedAt().isAfter(lastJoinedAt)
          || (session.leftAt() != null && session.leftAt().isAfter(observedAt)))
        throw invalid("session.timeline");
    }
    HashSet<String> addresses = new HashSet<>();
    for (Address address : recentAddresses) {
      if (!addresses.add(address.address())
          || address.lastSeenAt().isBefore(firstJoinedAt)
          || address.lastSeenAt().isAfter(observedAt)) throw invalid("address.timeline");
    }
  }

  /** Parsing is literal-only; hostname resolution is never permitted. */
  public static String normalizeAddress(String value) {
    if (value == null || value.isEmpty() || value.length() > 45 || !value.matches("[0-9A-Fa-f:.]+"))
      throw invalid("address.literal");
    if (!value.contains(":")) {
      String[] parts = value.split("\\.", -1);
      if (parts.length != 4) throw invalid("address.literal");
      for (String part : parts)
        if (!part.matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(part) > 255)
          throw invalid("address.literal");
      return value;
    }
    if (value.contains(".")) normalizeAddress(value.substring(value.lastIndexOf(':') + 1));
    try {
      return InetAddress.getByName(value).getHostAddress();
    } catch (UnknownHostException failure) {
      throw invalid("address.literal");
    }
  }

  private static Instant time(Instant value, String field) {
    if (value == null || value.isBefore(Instant.EPOCH)) throw invalid(field);
    return value.truncatedTo(ChronoUnit.MICROS);
  }

  private static void uuid(UUID value, String field) {
    if (value == null
        || (value.getMostSignificantBits() == 0 && value.getLeastSignificantBits() == 0))
      throw invalid(field);
  }

  private static void text(String value, int limit, boolean required, String field) {
    if (value == null
        || value.length() > limit
        || (required && value.isBlank())
        || value.codePoints().anyMatch(Character::isISOControl)) throw invalid(field);
  }

  private static void counter(Long value, String field) {
    if (value != null && (value < 0 || value == Long.MAX_VALUE)) throw invalid(field);
  }

  private static IllegalArgumentException invalid(String field) {
    return new IllegalArgumentException("Invalid telemetry field: " + field);
  }
}
