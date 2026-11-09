package cloud.mineplay.punishments.telemetry;

import static org.junit.jupiter.api.Assertions.*;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TelemetrySettingsTest {
  @Test
  void absentOptionalConfigurationUsesBoundedDefaults() {
    assertEquals(
        new TelemetrySettings(true, 30, 5), TelemetrySettings.read(new YamlConfiguration()));
  }

  @Test
  void eitherSwitchDisablesTelemetry() {
    var config = new YamlConfiguration();
    config.set("website.enabled", false);
    assertFalse(TelemetrySettings.read(config).enabled());
    config.set("website.enabled", true);
    config.set("player-telemetry.enabled", false);
    assertFalse(TelemetrySettings.read(config).enabled());
  }

  @ParameterizedTest
  @CsvSource({"9,5", "61,5", "30,0", "30,101"})
  void rejectsUnboundedOrStaleCollectionSettings(int seconds, int players) {
    assertThrows(
        IllegalArgumentException.class, () -> new TelemetrySettings(true, seconds, players));
  }

  @Test
  void rejectsStringAndOverflowSettings() {
    var config = new YamlConfiguration();
    config.set("player-telemetry.refresh-seconds", "30");
    assertThrows(IllegalArgumentException.class, () -> TelemetrySettings.read(config));
    config.set("player-telemetry.refresh-seconds", Long.MAX_VALUE);
    assertThrows(IllegalArgumentException.class, () -> TelemetrySettings.read(config));
  }
}
