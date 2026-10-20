package cloud.mineplay.punishments.integration;

import static org.junit.jupiter.api.Assertions.*;

import cloud.mineplay.punishments.appeals.AppealLinks;
import com.google.gson.JsonObject;
import java.util.*;
import org.junit.jupiter.api.Test;

class ApiProtocolTest {
  final AppealLinks links = new AppealLinks("https://www.mineplay.co.uk/appeal/");
  final String token = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);

  @Test
  void acceptsBackendLinkOnlyAtConfiguredOriginAndPath() {
    String valid = "https://www.mineplay.co.uk/appeal/" + token;
    assertEquals(valid, links.validate(valid));
    for (String invalid :
        List.of(
            "http://www.mineplay.co.uk/appeal/" + token,
            "https://evil.example/appeal/" + token,
            "https://www.mineplay.co.uk.evil.example/appeal/" + token,
            valid + "?token=secret",
            valid + "#secret",
            "https://user@www.mineplay.co.uk/appeal/" + token,
            "https://www.mineplay.co.uk/appeal/../" + token,
            "https://www.mineplay.co.uk/appeal/%2e%2e/" + token,
            "https://www.mineplay.co.uk/appeal/short"))
      assertThrows(IllegalArgumentException.class, () -> links.validate(invalid));
  }

  @Test
  void responseMustEchoRequestAndServerIdentityAndSchema() {
    String id = UUID.randomUUID().toString();
    JsonObject response = new JsonObject();
    response.addProperty("requestId", id);
    response.addProperty("serverId", "mineplay-smp");
    response.addProperty("schemaVersion", 1);
    assertNotNull(ApiProtocol.response(response.toString(), id, "mineplay-smp"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ApiProtocol.response(
                response.toString(), UUID.randomUUID().toString(), "mineplay-smp"));
    assertThrows(
        IllegalArgumentException.class,
        () -> ApiProtocol.response(response.toString(), id, "different-server"));
    response.addProperty("schemaVersion", 2);
    assertThrows(
        IllegalArgumentException.class,
        () -> ApiProtocol.response(response.toString(), id, "mineplay-smp"));
  }

  @Test
  void httpAndCredentialBearingRootAreRejected() {
    assertThrows(
        IllegalArgumentException.class, () -> ApiProtocol.httpsRoot("http://www.mineplay.co.uk"));
    assertThrows(
        IllegalArgumentException.class,
        () -> ApiProtocol.httpsRoot("https://user:secret@www.mineplay.co.uk"));
    assertThrows(
        IllegalArgumentException.class,
        () -> ApiProtocol.httpsRoot("https://www.mineplay.co.uk?secret=value"));
  }

  @Test
  void canonicalObjectOrderDoesNotAlterReplayHash() {
    JsonObject a = new JsonObject();
    a.addProperty("b", 2);
    a.addProperty("a", 1);
    JsonObject b = new JsonObject();
    b.addProperty("a", 1);
    b.addProperty("b", 2);
    assertEquals(ApiProtocol.canonical(a), ApiProtocol.canonical(b));
  }
}
