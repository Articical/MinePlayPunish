package cloud.mineplay.punishments.integration;

import com.google.gson.*;
import java.net.URI;
import java.time.Instant;
import java.util.*;

/** Strict wire validation. Error messages deliberately never include remote values or bodies. */
public final class ApiProtocol {
  public static final int SCHEMA_VERSION = 1;
  public static final int MAX_BODY_BYTES = 1_048_576;

  private ApiProtocol() {}

  public static URI httpsRoot(String value) {
    URI uri;
    try {
      uri = URI.create(value);
    } catch (RuntimeException e) {
      throw invalid("HTTPS endpoint");
    }
    if (!"https".equalsIgnoreCase(uri.getScheme())
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null
        || uri.getPath().contains("..")) throw invalid("HTTPS endpoint");
    return uri;
  }

  public static JsonObject object(JsonElement value, String name) {
    if (value == null || !value.isJsonObject()) throw invalid(name);
    return value.getAsJsonObject();
  }

  public static String string(JsonObject object, String name, int max) {
    JsonElement value = object.get(name);
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
      throw invalid(name);
    String text = value.getAsString();
    if (text.isBlank() || text.length() > max || text.chars().anyMatch(c -> c < 32 || c == 127))
      throw invalid(name);
    return text;
  }

  public static UUID uuid(JsonObject object, String name) {
    String value = string(object, name, 36);
    try {
      UUID id = UUID.fromString(value);
      if (!id.toString().equalsIgnoreCase(value)) throw invalid(name);
      return id;
    } catch (IllegalArgumentException e) {
      throw invalid(name);
    }
  }

  public static long integer(JsonObject object, String name, long min, long max) {
    JsonElement value = object.get(name);
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
      throw invalid(name);
    try {
      long number = value.getAsBigDecimal().longValueExact();
      if (number < min || number > max) throw invalid(name);
      return number;
    } catch (ArithmeticException | NumberFormatException e) {
      throw invalid(name);
    }
  }

  public static Instant instant(JsonObject object, String name) {
    try {
      String value = string(object, name, 40);
      if (!value.endsWith("Z")) throw invalid(name);
      return Instant.parse(value);
    } catch (RuntimeException e) {
      throw invalid(name);
    }
  }

  public static boolean bool(JsonObject object, String name) {
    JsonElement value = object.get(name);
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean())
      throw invalid(name);
    return value.getAsBoolean();
  }

  public static JsonObject response(String body, String requestId, String serverId) {
    try {
      if (body.length() > MAX_BODY_BYTES) throw invalid("response size");
      JsonObject result = object(JsonParser.parseString(body), "response");
      integer(result, "schemaVersion", 1, 1);
      if (!uuid(result, "requestId").toString().equals(requestId)
          || !string(result, "serverId", 64).equals(serverId)) throw invalid("response identity");
      return result;
    } catch (JsonParseException e) {
      throw invalid("response JSON");
    }
  }

  public static void only(JsonObject object, String... fields) {
    Set<String> allowed = Set.of(fields);
    if (object.keySet().stream().anyMatch(key -> !allowed.contains(key)))
      throw invalid("unknown field");
  }

  public static String canonical(JsonElement value) {
    if (value.isJsonObject()) {
      JsonObject sorted = new JsonObject();
      new TreeSet<>(value.getAsJsonObject().keySet())
          .forEach(
              key ->
                  sorted.add(
                      key, JsonParser.parseString(canonical(value.getAsJsonObject().get(key)))));
      return sorted.toString();
    }
    if (value.isJsonArray()) {
      JsonArray array = new JsonArray();
      value.getAsJsonArray().forEach(item -> array.add(JsonParser.parseString(canonical(item))));
      return array.toString();
    }
    return value.toString();
  }

  public static IllegalArgumentException invalid(String field) {
    return new IllegalArgumentException("Invalid API " + field);
  }
}
