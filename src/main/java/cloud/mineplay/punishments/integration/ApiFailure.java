package cloud.mineplay.punishments.integration;

import java.time.Instant;

/** Controlled diagnostic fields only: never retain response bodies or credentials in exceptions. */
final class ApiFailure extends RuntimeException {
  final String category;
  final int status;
  final Instant retryAt;

  ApiFailure(String category) {
    this(category, 0, null);
  }

  ApiFailure(String category, int status, Instant retryAt) {
    super(category);
    this.category = category;
    this.status = status;
    this.retryAt = retryAt;
  }
}
