package cloud.mineplay.punishments.chat;

import cloud.mineplay.punishments.MineplayPunishments;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/** Public chat cancellation only. Matching uses a prevalidated immutable configuration snapshot. */
public final class ChatFilterListener implements Listener {
  private static final PlainTextComponentSerializer PLAIN =
      PlainTextComponentSerializer.plainText();
  private final MineplayPunishments plugin;
  private volatile ChatFilter filter;

  public ChatFilterListener(MineplayPunishments plugin, ChatFilter filter) {
    this.plugin = Objects.requireNonNull(plugin, "plugin");
    this.filter = Objects.requireNonNull(filter, "filter");
  }

  /** Publish only after every stage of the plugin reload has succeeded. */
  public void replace(ChatFilter replacement) {
    filter = Objects.requireNonNull(replacement, "replacement");
  }

  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void earlyChat(AsyncChatEvent event) {
    filter(event);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void lateChat(AsyncChatEvent event) {
    filter(event);
  }

  private void filter(AsyncChatEvent event) {
    ChatFilter current = filter;
    if (!current.active() || event.isCancelled()) return;
    Component original = event.originalMessage();
    Component message = event.message();
    if (!current.blocks(PLAIN.serialize(original))
        && (message == original || !current.blocks(PLAIN.serialize(message)))) return;
    event.setCancelled(true);
    Player player = event.getPlayer();
    plugin.onMain(
        () -> {
          if (player.isOnline()) player.sendMessage(plugin.messages().text("chat-filter-blocked"));
        });
  }
}
