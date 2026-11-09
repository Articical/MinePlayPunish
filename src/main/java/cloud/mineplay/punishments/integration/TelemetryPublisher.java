package cloud.mineplay.punishments.integration;

import cloud.mineplay.punishments.telemetry.TelemetryRepository;
import cloud.mineplay.punishments.telemetry.TelemetryRepository.Pending;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/** Dedicated durable /players lane on the shared sync worker and HTTPS request path. */
final class TelemetryPublisher {
  private static final int PLAYER_BUDGET = ApiProtocol.MAX_BODY_BYTES - 1024;
  private final TelemetryRepository repository;
  private final SyncSettings settings;
  private final Clock clock;
  private final Function<JsonObject, JsonObject> send;
  private Instant nextAttempt = Instant.MIN;
  private int failures;
  private boolean initialized;
  private boolean isolate;
  private String lastFailure;
  private long rejected;
  private volatile String status = "awaiting publication";

  TelemetryPublisher(
      TelemetryRepository repository,
      SyncSettings settings,
      Clock clock,
      Function<JsonObject, JsonObject> send) {
    this.repository = repository;
    this.settings = settings;
    this.clock = clock;
    this.send = send;
  }

  void initialize() {
    try {
      repository.initialize().join();
      initialized = true;
    } catch (RuntimeException failure) {
      // Telemetry's additive schema must not prevent moderation startup or polling.
      lastFailure = "PLAYER_STORAGE_UNAVAILABLE";
      nextAttempt = clock.instant().plusSeconds(settings.backoffSeconds());
      status = lastFailure + "; retry after " + nextAttempt;
    }
  }

  String status() {
    return status;
  }

  SyncEngine.CycleResult cycle() {
    if (clock.instant().isBefore(nextAttempt)) return currentResult();
    List<Pending> pending = List.of();
    try {
      if (!initialized) {
        repository.initialize().join();
        initialized = true;
      }
      // The repository budgets the array, while the request also wraps it in {"players":...}.
      pending =
          repository
              .nextBatch(isolate ? 1 : Math.min(100, settings.batchSize()), PLAYER_BUDGET - 32)
              .join();
      if (pending.isEmpty()) {
        failures = 0;
        lastFailure = null;
        status = "idle; rejected_this_run=" + rejected;
        return currentResult();
      }
      JsonObject payload = payload(pending);
      JsonObject response = send.apply(payload);
      ApiProtocol.only(
          response,
          "schemaVersion",
          "requestId",
          "serverId",
          "acceptedPlayerIds",
          "ignoredPlayerIds");
      Set<UUID> expected = new HashSet<>();
      pending.forEach(row -> expected.add(row.playerId()));
      Set<UUID> accepted = ids(response, "acceptedPlayerIds", expected);
      Set<UUID> ignored = ids(response, "ignoredPlayerIds", expected);
      Set<UUID> all = new HashSet<>(accepted);
      for (UUID id : ignored)
        if (!all.add(id)) throw ApiProtocol.invalid("player acknowledgement overlap");
      if (!all.equals(expected)) throw ApiProtocol.invalid("player acknowledgement coverage");
      repository.acknowledge(pending, accepted, ignored).join();
      failures = 0;
      lastFailure = null;
      isolate = false;
      nextAttempt = clock.instant().plusSeconds(settings.intervalSeconds());
      status =
          "published="
              + accepted.size()
              + "; ignored="
              + ignored.size()
              + "; rejected_this_run="
              + rejected;
      return currentResult();
    } catch (RuntimeException failure) {
      Throwable cause = SyncEngine.rootCause(failure);
      String category = SyncEngine.failureCategory(cause);
      if (cause instanceof ApiFailure api
          && (api.status == 400 || api.status == 409)
          && !pending.isEmpty()) {
        if (pending.size() > 1) {
          // The atomic rejection identifies no guilty item. Keep every frozen revision;
          // isolate one item next cycle so healthy historical players are never discarded.
          isolate = true;
        } else {
          repository.reject(pending, category).join();
          rejected++;
          isolate = false;
        }
        nextAttempt = clock.instant().plusSeconds(settings.intervalSeconds());
      } else {
        failures = Math.min(31, failures + 1);
        long delay =
            category.equals("AUTHENTICATION") || category.equals("CREDENTIAL_MISSING")
                ? settings.maxBackoffSeconds()
                : settings.backoff(failures - 1);
        nextAttempt = clock.instant().plusSeconds(delay);
        if (cause instanceof ApiFailure api
            && api.retryAt != null
            && api.retryAt.isAfter(nextAttempt)) nextAttempt = api.retryAt;
      }
      lastFailure = category;
      status = category + "; retry after " + nextAttempt + "; rejected_this_run=" + rejected;
      return currentResult();
    }
  }

  private SyncEngine.CycleResult currentResult() {
    return lastFailure == null
        ? new SyncEngine.CycleResult(SyncEngine.CycleOutcome.SUCCEEDED, null)
        : new SyncEngine.CycleResult(SyncEngine.CycleOutcome.FAILED, lastFailure);
  }

  static JsonObject payload(List<Pending> rows) {
    if (rows.isEmpty() || rows.size() > 100) throw ApiProtocol.invalid("player batch size");
    JsonArray players = new JsonArray();
    Set<UUID> ids = new HashSet<>();
    for (Pending row : rows) {
      JsonObject player = ApiProtocol.object(JsonParser.parseString(row.json()), "player snapshot");
      if (!ApiProtocol.uuid(player, "playerUuid").equals(row.playerId())
          || ApiProtocol.integer(player, "revision", 1, Long.MAX_VALUE - 1) != row.revision()
          || !ids.add(row.playerId())) throw ApiProtocol.invalid("player snapshot identity");
      boundedArray(player, "sessions", 100);
      boundedArray(player, "recentAddresses", 5);
      players.add(player);
    }
    JsonObject result = new JsonObject();
    result.add("players", players);
    if (result.toString().getBytes(StandardCharsets.UTF_8).length > PLAYER_BUDGET)
      throw ApiProtocol.invalid("player batch size");
    return result;
  }

  private static void boundedArray(JsonObject player, String name, int maximum) {
    JsonElement value = player.get(name);
    if (value == null || !value.isJsonArray() || value.getAsJsonArray().size() > maximum)
      throw ApiProtocol.invalid("player " + name);
  }

  private static Set<UUID> ids(JsonObject response, String field, Set<UUID> expected) {
    JsonElement value = response.get(field);
    if (value == null || !value.isJsonArray()) throw ApiProtocol.invalid("player acknowledgement");
    Set<UUID> result = new HashSet<>();
    for (JsonElement item : value.getAsJsonArray()) {
      if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString())
        throw ApiProtocol.invalid("player acknowledgement");
      UUID id;
      try {
        String text = item.getAsString();
        id = UUID.fromString(text);
        if (!id.toString().equalsIgnoreCase(text))
          throw ApiProtocol.invalid("player acknowledgement");
      } catch (IllegalArgumentException invalid) {
        throw ApiProtocol.invalid("player acknowledgement");
      }
      if (!expected.contains(id) || !result.add(id))
        throw ApiProtocol.invalid("player acknowledgement");
    }
    return Set.copyOf(result);
  }
}
