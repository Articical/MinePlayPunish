package cloud.mineplay.punishments.afk;

import static org.junit.jupiter.api.Assertions.*;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class AfkSettingsTest {
  @Test
  void oldConfigurationGetsTheRequestedFifteenMinuteDefault() {
    assertEquals(new AfkSettings(true, 900, 120, 5), AfkSettings.read(new YamlConfiguration()));
  }

  @Test
  void configurationCanDisableAndTuneEveryTiming() {
    var config = new YamlConfiguration();
    config.set("anti-afk.enabled", false);
    config.set("anti-afk.idle-timeout-seconds", 1800);
    config.set("anti-afk.warning-seconds", 300);
    config.set("anti-afk.check-interval-seconds", 10);
    assertEquals(new AfkSettings(false, 1800, 300, 10), AfkSettings.read(config));
  }

  @ParameterizedTest
  @CsvSource({
    "59,5,1", "86401,120,5", "900,4,1", "900,900,5", "900,901,5",
    "900,120,0", "900,120,31", "900,5,6", "0,0,0", "900,-1,1"
  })
  void rejectsUnsafeOrUnboundedIntervals(int timeout, int warning, int checks) {
    assertThrows(
        IllegalArgumentException.class, () -> new AfkSettings(true, timeout, warning, checks));
  }

  @ParameterizedTest
  @ValueSource(strings = {"true", "false", "yes", "1"})
  void enabledFlagMustBeActualBoolean(String value) {
    var config = new YamlConfiguration();
    config.set("anti-afk.enabled", value);
    assertThrows(IllegalArgumentException.class, () -> AfkSettings.read(config));
  }

  @ParameterizedTest
  @ValueSource(strings = {"idle-timeout-seconds", "warning-seconds", "check-interval-seconds"})
  void numericStringsDecimalsAndOverflowAreRejected(String suffix) {
    var config = new YamlConfiguration();
    for (Object value : new Object[] {"900", 900.0, Long.MAX_VALUE}) {
      config.set("anti-afk." + suffix, value);
      assertThrows(IllegalArgumentException.class, () -> AfkSettings.read(config));
    }
  }

  @Test
  void smallestAndLargestSupportedSettingsAreValid() {
    assertDoesNotThrow(() -> new AfkSettings(true, 60, 5, 1));
    assertDoesNotThrow(() -> new AfkSettings(true, 86_400, 86_399, 30));
  }
}
