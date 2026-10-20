package cloud.mineplay.punishments.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MessagesTest {
  @TempDir Path folder;

  @Test
  void existingMessagesFileGetsReadableFilterFallbackWithoutBeingOverwritten() throws Exception {
    Path file = writeMessages(null);
    String original = Files.readString(file);
    assertEquals(
        "Your message was blocked by the chat filter. Please rephrase it.",
        PlainTextComponentSerializer.plainText().serialize(messages().text("chat-filter-blocked")));
    assertEquals(original, Files.readString(file));
  }

  @Test
  void administratorCanCustomizeFilterFeedback() throws Exception {
    writeMessages("<gold>Please keep chat friendly.</gold>");
    assertEquals(
        "Please keep chat friendly.",
        PlainTextComponentSerializer.plainText().serialize(messages().text("chat-filter-blocked")));
  }

  private Path writeMessages(String feedback) throws Exception {
    YamlConfiguration config;
    try (var stream = getClass().getResourceAsStream("/messages.yml")) {
      config = new YamlConfiguration();
      config.loadFromString(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
    }
    config.set("chat-filter-blocked", feedback);
    Path file = folder.resolve("messages.yml");
    Files.writeString(file, config.saveToString());
    return file;
  }

  private Messages messages() {
    JavaPlugin plugin = mock(JavaPlugin.class);
    when(plugin.getDataFolder()).thenReturn(folder.toFile());
    return new Messages(plugin);
  }
}
