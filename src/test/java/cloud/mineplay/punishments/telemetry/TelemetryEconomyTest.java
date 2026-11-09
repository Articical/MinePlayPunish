package cloud.mineplay.punishments.telemetry;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.ServicesManager;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

class TelemetryEconomyTest {
  private MockedStatic<Bukkit> bukkit;
  private Server server;
  private PluginManager plugins;
  private ServicesManager services;
  private Plugin vault;
  private Economy economy;
  private OfflinePlayer player;

  @BeforeEach
  void setup() {
    bukkit = mockStatic(Bukkit.class);
    bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
    server = mock(Server.class);
    plugins = mock(PluginManager.class);
    services = mock(ServicesManager.class);
    vault = mock(Plugin.class);
    economy = mock(Economy.class);
    player = mock(OfflinePlayer.class);
    when(server.getPluginManager()).thenReturn(plugins);
    when(server.getServicesManager()).thenReturn(services);
    when(plugins.getPlugin("Vault")).thenReturn(vault);
    when(vault.isEnabled()).thenReturn(true);
    when(services.getRegistration(Economy.class))
        .thenReturn(
            new RegisteredServiceProvider<>(Economy.class, economy, ServicePriority.Normal, vault));
    when(economy.isEnabled()).thenReturn(true);
    when(economy.hasAccount(player)).thenReturn(true);
    when(economy.getBalance(player)).thenReturn(1250.5);
    when(economy.getName()).thenReturn("Economy Provider");
    when(economy.currencyNamePlural()).thenReturn("coins");
  }

  @AfterEach
  void cleanup() {
    bukkit.close();
  }

  @Test
  void availableHasExactDecimalStringAndProviderCurrency() {
    var result = TelemetryEconomy.read(server, player);
    assertEquals(TelemetryObservation.Status.AVAILABLE, result.status());
    assertEquals("1250.5", result.balance());
    assertEquals("Economy Provider", result.provider());
    assertEquals("coins", result.currency());
    verify(economy, never()).format(anyDouble());
    verify(economy, never()).createPlayerAccount(any(OfflinePlayer.class));
  }

  @Test
  void absentVaultIsNotInstalledWithoutTouchingServiceClasses() {
    when(plugins.getPlugin("Vault")).thenReturn(null);
    assertEquals(
        TelemetryObservation.Status.NOT_INSTALLED, TelemetryEconomy.read(server, player).status());
    verifyNoInteractions(services);
  }

  @Test
  void disabledVaultIsUnavailable() {
    when(vault.isEnabled()).thenReturn(false);
    assertUnavailable();
    verifyNoInteractions(services);
  }

  @Test
  void missingProviderIsUnavailable() {
    when(services.getRegistration(Economy.class)).thenReturn(null);
    assertUnavailable();
  }

  @Test
  void disabledProviderIsUnavailable() {
    when(economy.isEnabled()).thenReturn(false);
    assertUnavailable();
    verify(economy, never()).getBalance(player);
  }

  @Test
  void missingAccountDoesNotCreateOrReadAnAccount() {
    when(economy.hasAccount(player)).thenReturn(false);
    var result = TelemetryEconomy.read(server, player);
    assertEquals(TelemetryObservation.Status.NO_ACCOUNT, result.status());
    assertNull(result.balance());
    verify(economy, never()).getBalance(player);
    verify(economy, never()).createPlayerAccount(player);
  }

  @Test
  void brokenProviderReturnsNoPrivateExceptionInformation() {
    when(economy.getBalance(player)).thenThrow(new IllegalStateException("private player balance"));
    assertUnavailable();
    verify(server, never()).getLogger();
  }

  @Test
  void unsupportedProviderLinkageIsUnavailable() {
    when(economy.hasAccount(player)).thenThrow(new NoSuchMethodError("private data"));
    assertUnavailable();
  }

  @Test
  void currencyCanUseProvidersSingularLabel() {
    when(economy.currencyNamePlural()).thenReturn("");
    when(economy.currencyNameSingular()).thenReturn("coin");
    assertEquals("coin", TelemetryEconomy.read(server, player).currency());
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "  ", "coin\nsecret"})
  void missingOrUnsafeCurrencyIsUnavailable(String currency) {
    when(economy.currencyNamePlural()).thenReturn(currency);
    when(economy.currencyNameSingular()).thenReturn(currency);
    assertUnavailable();
  }

  @Test
  void oversizedLabelsAreUnavailable() {
    when(economy.getName()).thenReturn("x".repeat(129));
    assertUnavailable();
    when(economy.getName()).thenReturn("Provider");
    when(economy.currencyNamePlural()).thenReturn("x".repeat(33));
    assertUnavailable();
  }

  @ParameterizedTest
  @CsvSource({
    "0.0,0",
    "-0.0,0",
    "-10.25,-10.25",
    "0.000001,0.000001",
    "1.234567,1.234567",
    "1.0E17,100000000000000000"
  })
  void supportedNumbersHavePlainExactDecimalEncoding(double input, String expected) {
    assertEquals(expected, TelemetryEconomy.decimal(input));
  }

  @ParameterizedTest
  @ValueSource(
      doubles = {
        Double.NaN,
        Double.POSITIVE_INFINITY,
        Double.NEGATIVE_INFINITY,
        1.0E18,
        -1.0E18,
        0.0000001,
        1.2345678,
        0.30000000000000004
      })
  void unsupportedNumbersNeverRoundOrProduceAnAvailableBalance(double input) {
    when(economy.getBalance(player)).thenReturn(input);
    assertNull(TelemetryEconomy.decimal(input));
    assertUnavailable();
  }

  @Test
  void refusesProviderAccessFromAnAsyncThread() {
    bukkit.when(Bukkit::isPrimaryThread).thenReturn(false);
    assertThrows(IllegalStateException.class, () -> TelemetryEconomy.read(server, player));
    verifyNoInteractions(plugins, services, economy);
  }

  private void assertUnavailable() {
    var result = TelemetryEconomy.read(server, player);
    assertEquals(TelemetryObservation.Status.UNAVAILABLE, result.status());
    assertNull(result.balance());
  }
}
