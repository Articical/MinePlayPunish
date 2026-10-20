package cloud.mineplay.punishments.enforcement;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cloud.mineplay.punishments.MineplayPunishments;
import cloud.mineplay.punishments.config.Messages;
import cloud.mineplay.punishments.punishment.Models.*;
import cloud.mineplay.punishments.punishment.PunishmentService;
import com.destroystokyo.paper.profile.PlayerProfile;
import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.event.connection.PlayerConnectionValidateLoginEvent;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EnforcementListenerTest {
  private EnforcementListener listener;
  private PunishmentService service;
  private Player player;
  private UUID target;

  @BeforeEach
  void setup() {
    MineplayPunishments plugin = mock(MineplayPunishments.class);
    service = mock(PunishmentService.class);
    when(service.healthy()).thenReturn(true);
    target = UUID.randomUUID();
    player = mock(Player.class);
    when(player.getUniqueId()).thenReturn(target);
    var server = mock(org.bukkit.Server.class);
    when(player.getServer()).thenReturn(server);
    doReturn(List.of(player)).when(server).getOnlinePlayers();
    var config = new YamlConfiguration();
    config.set("punishments.mutes.blocked-commands", List.of("Msg", "reply"));
    config.set("punishments.mutes.block-private-messages", true);
    config.set("punishments.mutes.block-public-chat", true);
    var messages = mock(Messages.class);
    when(messages.text("storage-unavailable")).thenReturn(Component.text("Unavailable"));
    when(messages.punishment(anyString(), any())).thenReturn(Component.text("Punishment"));
    when(plugin.getConfig()).thenReturn(config);
    when(plugin.punishments()).thenReturn(service);
    when(plugin.messages()).thenReturn(messages);
    listener = new EnforcementListener(plugin);
  }

  private Punishment punishment(Type type) {
    return new Punishment(
        UUID.randomUUID(),
        "MP-TEST",
        target,
        "Test",
        type,
        "Reason",
        "CONSOLE",
        Source.CONSOLE,
        Instant.now(),
        null,
        Status.ACTIVE,
        1,
        null);
  }

  @Test
  void namespacedMuteAndMixedCaseConfigurationBlockCommunicationOnly() {
    when(service.active(target, Type.MUTE)).thenReturn(Optional.of(punishment(Type.MUTE)));
    var msg = new PlayerCommandPreprocessEvent(player, "/essentials:MSG Someone hello");
    listener.command(msg);
    assertTrue(msg.isCancelled());
    var spawn = new PlayerCommandPreprocessEvent(player, "/spawn");
    listener.command(spawn);
    assertFalse(spawn.isCancelled());
    verify(service, times(1)).active(target, Type.MUTE);
  }

  @Test
  void unavailableCacheBlocksCommunicationWithoutDatabaseIo() {
    when(service.healthy()).thenReturn(false);
    var chat = mock(AsyncChatEvent.class);
    when(chat.getPlayer()).thenReturn(player);
    listener.chat(chat);
    verify(chat).setCancelled(true);
    verify(service, never()).refresh();
    var msg = new PlayerCommandPreprocessEvent(player, "/msg Someone hello");
    listener.command(msg);
    assertTrue(msg.isCancelled());
  }

  @Test
  void ordinaryChatUsesOnlyTheCachedMute() {
    when(service.active(target, Type.MUTE)).thenReturn(Optional.of(punishment(Type.MUTE)));
    var chat = mock(AsyncChatEvent.class);
    when(chat.getPlayer()).thenReturn(player);
    listener.chat(chat);
    verify(chat).setCancelled(true);
    verify(service, never()).refresh();
  }

  @Test
  void connectionValidationRechecksBanAfterEarlierAsyncLogin() {
    var connection = mock(PlayerConfigurationConnection.class);
    var profile = mock(PlayerProfile.class);
    when(profile.getId()).thenReturn(target);
    when(connection.getProfile()).thenReturn(profile);
    var event = mock(PlayerConnectionValidateLoginEvent.class);
    when(event.getConnection()).thenReturn(connection);
    when(service.active(target, Type.BAN)).thenReturn(Optional.of(punishment(Type.BAN)));
    listener.validateConnection(event);
    verify(event).kickMessage(any(Component.class));
  }

  @Test
  void connectionValidationRejectsUnverifiedCache() {
    when(service.healthy()).thenReturn(false);
    var event = mock(PlayerConnectionValidateLoginEvent.class);
    listener.validateConnection(event);
    verify(event).kickMessage(any(Component.class));
  }
}
