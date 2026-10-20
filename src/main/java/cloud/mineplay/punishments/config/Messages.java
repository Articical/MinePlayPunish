package cloud.mineplay.punishments.config;

import cloud.mineplay.punishments.punishment.Models.Punishment;
import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class Messages {
  private final Map<String, String> templates;
  private final MiniMessage mini = MiniMessage.miniMessage();
  private static final String CHAT_FILTER_BLOCKED =
      "<red>Your message was blocked by the chat filter. Please rephrase it.</red>";
  private static final String PUNISHMENT_ANNOUNCEMENT =
      "<gold>Mineplay</gold> <dark_gray>»</dark_gray> <yellow><actor> <action> <target>.</yellow>"
          + " <gray>Reason: <white><reason></white> [<reference>]</gray>";

  public Messages(JavaPlugin plugin) {
    File file = new File(plugin.getDataFolder(), "messages.yml");
    if (!file.exists()) plugin.saveResource("messages.yml", false);
    YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
    templates =
        yaml.getValues(false).entrySet().stream()
            .collect(
                java.util.stream.Collectors.toUnmodifiableMap(
                    Map.Entry::getKey, e -> String.valueOf(e.getValue())));
    for (String key :
        java.util.List.of(
            "ban",
            "mute",
            "warning",
            "kick",
            "appeal-pending",
            "storage-unavailable",
            "error",
            "success")) {
      if (!templates.containsKey(key))
        throw new IllegalArgumentException("Missing messages.yml key: " + key);
    }
  }

  public Component text(String key, String... replacements) {
    TagResolver.Builder tags = TagResolver.builder();
    for (int i = 0; i + 1 < replacements.length; i += 2)
      tags.resolver(Placeholder.unparsed(replacements[i], replacements[i + 1]));
    // Existing installations keep their messages.yml when upgrading.
    String fallback =
        switch (key) {
          case "chat-filter-blocked" -> CHAT_FILTER_BLOCKED;
          case "punishment-announcement" -> PUNISHMENT_ANNOUNCEMENT;
          default -> key;
        };
    return mini.deserialize(templates.getOrDefault(key, fallback), tags.build());
  }

  public Component punishment(String key, Punishment p) {
    Component appeal =
        p.appealUrl() == null || p.appealUrl().isBlank()
            ? text("appeal-pending", "reference", p.reference())
            : text("appeal-link", "url", p.appealUrl())
                .clickEvent(ClickEvent.openUrl(p.appealUrl()));
    return mini.deserialize(
        templates.getOrDefault(key, key),
        Placeholder.unparsed("reason", p.reason()),
        Placeholder.unparsed("reference", p.reference()),
        Placeholder.unparsed("id", p.id().toString()),
        Placeholder.unparsed(
            "expires", p.expiresAt() == null ? "Permanent" : p.expiresAt().toString()),
        Placeholder.unparsed("remaining", remaining(p.expiresAt())),
        Placeholder.component("appeal", appeal));
  }

  public static String remaining(Instant expires) {
    if (expires == null) return "Permanent";
    long seconds = Math.max(0, Duration.between(Instant.now(), expires).getSeconds());
    return seconds / 86400
        + "d "
        + seconds % 86400 / 3600
        + "h "
        + seconds % 3600 / 60
        + "m "
        + seconds % 60
        + "s";
  }
}
