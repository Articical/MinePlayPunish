package cloud.mineplay.punishments.config;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class PluginSettingsTest {
  private YamlConfiguration defaults() {
    return YamlConfiguration.loadConfiguration(
        new InputStreamReader(
            getClass().getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
  }

  @Test
  void defaultsAreValidAndUseCanonicalWebsite() {
    var config = defaults();
    var settings = PluginSettings.read(config, Path.of("test-plugin"));
    assertEquals(10, settings.minimumBlocks());
    assertEquals(10000, settings.maximumBlocks());
    assertEquals("https://www.mineplay.co.uk", config.getString("website.base-url"));
  }

  @Test
  void rejectsPathEscape() {
    var config = defaults();
    config.set("database.file", "../another-plugin/private.db");
    assertThrows(
        IllegalArgumentException.class, () -> PluginSettings.read(config, Path.of("test-plugin")));
  }

  @Test
  void rejectsInsecureOrForeignUrls() {
    for (String url :
        new String[] {
          "http://www.mineplay.co.uk/api",
          "https://secret@www.mineplay.co.uk/api",
          "https://evil.example/api",
          "https://www.mineplay.co.uk/api?token=x"
        }) {
      var config = defaults();
      config.set("website.api-url", url);
      assertThrows(
          IllegalArgumentException.class,
          () -> PluginSettings.read(config, Path.of("test-plugin")));
    }
  }

  @Test
  void rejectsUnsafeOrUnsupportedModes() {
    for (String key :
        new String[] {
          "database.wal-enabled",
          "sync.durable-inbox",
          "community-service.mining.suppress-drops",
          "community-service.restrictions.prevent-teleport-escape"
        }) {
      var config = defaults();
      config.set(key, false);
      assertThrows(
          IllegalArgumentException.class,
          () -> PluginSettings.read(config, Path.of("test-plugin")));
    }
    var config = defaults();
    config.set("punishments.escalation.enabled", true);
    assertThrows(
        IllegalArgumentException.class, () -> PluginSettings.read(config, Path.of("test-plugin")));
  }

  @Test
  void rejectsInconsistentBounds() {
    var config = defaults();
    config.set("community-service.maximum-blocks", 2);
    assertThrows(
        IllegalArgumentException.class, () -> PluginSettings.read(config, Path.of("test-plugin")));
  }
}
