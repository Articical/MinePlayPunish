package cloud.mineplay.punishments.integration;

import java.math.BigInteger;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/** Parses only the standard delay/date header. Its untrusted input is never logged. */
final class RetryAfter {
  private RetryAfter() {}

  static Optional<Instant> deadline(String header, Instant now) {
    if (header == null || header.isBlank() || header.length() > 128) return Optional.empty();
    String value = header.trim();
    try {
      Instant result;
      if (value.matches("[0-9]+")) {
        BigInteger seconds = new BigInteger(value);
        BigInteger available =
            BigInteger.valueOf(Instant.MAX.getEpochSecond())
                .subtract(BigInteger.valueOf(now.getEpochSecond()));
        result =
            seconds.compareTo(available) >= 0
                ? Instant.MAX
                : now.plusSeconds(seconds.longValueExact());
      } else {
        result = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
      }
      return Optional.of(result.isBefore(now) ? now : result);
    } catch (RuntimeException invalid) {
      return Optional.empty();
    }
  }
}
