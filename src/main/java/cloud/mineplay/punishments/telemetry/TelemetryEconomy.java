package cloud.mineplay.punishments.telemetry;

import java.math.BigDecimal;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;

/** Read-only Vault adapter. The nested bridge is loaded only when Vault is present. */
final class TelemetryEconomy {
  private TelemetryEconomy() {}

  static TelemetryObservation.Economy read(Server server, OfflinePlayer player) {
    if (!Bukkit.isPrimaryThread())
      throw new IllegalStateException("Vault telemetry requires the server thread");
    Plugin vault = server.getPluginManager().getPlugin("Vault");
    if (vault == null) return unavailable(TelemetryObservation.Status.NOT_INSTALLED);
    if (!vault.isEnabled()) return unavailable(TelemetryObservation.Status.UNAVAILABLE);
    try {
      return VaultBridge.read(server, player);
    } catch (RuntimeException | LinkageError failure) {
      // Provider errors may contain account/balance data. Never log their text or stack trace.
      return unavailable(TelemetryObservation.Status.UNAVAILABLE);
    }
  }

  static TelemetryObservation.Economy unavailable(TelemetryObservation.Status status) {
    return new TelemetryObservation.Economy(status, null, null, null);
  }

  static String decimal(double value) {
    if (!Double.isFinite(value)) return null;
    BigDecimal number = BigDecimal.valueOf(value).stripTrailingZeros();
    if (number.scale() > 6 || Math.max(1, number.precision() - number.scale()) > 18) return null;
    return number.toPlainString();
  }

  private static boolean label(String value, int maximum) {
    return value != null
        && !value.isBlank()
        && value.length() <= maximum
        && value.codePoints().noneMatch(Character::isISOControl);
  }

  private static final class VaultBridge {
    static TelemetryObservation.Economy read(Server server, OfflinePlayer player) {
      var registration =
          server.getServicesManager().getRegistration(net.milkbowl.vault.economy.Economy.class);
      if (registration == null) return unavailable(TelemetryObservation.Status.UNAVAILABLE);
      var provider = registration.getProvider();
      if (provider == null || !provider.isEnabled())
        return unavailable(TelemetryObservation.Status.UNAVAILABLE);
      if (!provider.hasAccount(player)) return unavailable(TelemetryObservation.Status.NO_ACCOUNT);
      String balance = decimal(provider.getBalance(player));
      String name = provider.getName();
      String currency = provider.currencyNamePlural();
      if (!label(currency, 32)) currency = provider.currencyNameSingular();
      if (balance == null || !label(name, 128) || !label(currency, 32))
        return unavailable(TelemetryObservation.Status.UNAVAILABLE);
      return new TelemetryObservation.Economy(
          TelemetryObservation.Status.AVAILABLE, balance, name, currency);
    }
  }
}
