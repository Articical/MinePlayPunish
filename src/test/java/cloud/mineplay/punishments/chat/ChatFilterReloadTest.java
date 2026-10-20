package cloud.mineplay.punishments.chat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cloud.mineplay.punishments.MineplayPunishments;
import cloud.mineplay.punishments.communityservice.CommunityServiceManager;
import cloud.mineplay.punishments.config.Messages;
import cloud.mineplay.punishments.config.PluginSettings;
import cloud.mineplay.punishments.enforcement.EnforcementListener;
import cloud.mineplay.punishments.gui.PunishMenu;
import cloud.mineplay.punishments.integration.SyncClient;
import cloud.mineplay.punishments.punishment.PunishmentService;
import cloud.mineplay.punishments.storage.Database;
import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import net.kyori.adventure.chat.SignedMessage;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

/**
 * Uses the plugin's real reload method, replacing only external service construction and settings.
 */
class ChatFilterReloadTest {
  @TempDir Path directory;
  private MineplayPunishments plugin;
  private YamlConfiguration config;
  private ChatFilterListener listener;
  private PluginSettings previousSettings;
  private PluginSettings nextSettings;
  private Messages previousMessages;
  private CommunityServiceManager community;
  private EnforcementListener enforcement;
  private PunishMenu menus;
  private SyncClient previousSync;

  @BeforeEach
  void setup() throws Exception {
    plugin = mock(MineplayPunishments.class, CALLS_REAL_METHODS);
    config = new YamlConfiguration();
    config.set("chat-filter.enabled", true);
    config.set("chat-filter.banned-words", List.of("forbidden"));
    config.set("chat-filter.regex-patterns", List.of());
    doReturn(config).when(plugin).getConfig();
    doReturn(directory.toFile()).when(plugin).getDataFolder();
    doReturn(mock(Logger.class)).when(plugin).getLogger();
    doNothing().when(plugin).onMain(any(Runnable.class));
    previousSettings = settings();
    nextSettings = settings();
    previousMessages = mock(Messages.class);
    community = mock(CommunityServiceManager.class);
    enforcement = mock(EnforcementListener.class);
    menus = mock(PunishMenu.class);
    previousSync = mock(SyncClient.class);
    when(previousSync.stoppedCleanly()).thenReturn(true);
    listener = new ChatFilterListener(plugin, ChatFilter.read(config));
    field("settings", previousSettings);
    field("messages", previousMessages);
    field("database", mock(Database.class));
    field("punishments", mock(PunishmentService.class));
    field("community", community);
    field("enforcement", enforcement);
    field("menus", menus);
    field("sync", previousSync);
    field("chatFilter", listener);
  }

  @Test
  void invalidFilterReloadKeepsLastGoodRulesAndDoesNotTouchOtherServices() {
    String previous = config.saveToString();
    doAnswer(
            call -> {
              config.set("chat-filter.regex-patterns", List.of("("));
              return null;
            })
        .when(plugin)
        .reloadConfig();
    assertThrows(IllegalArgumentException.class, plugin::reloadSettings);
    assertEquals(previous, config.saveToString());
    assertSame(previousSettings, plugin.settings());
    assertSame(previousMessages, plugin.messages());
    assertSame(previousSync, plugin.sync());
    verify(menus, never()).closeAll();
    verify(community, never()).reload();
    verify(enforcement, never()).reload();
    verify(previousSync, never()).stop();
    assertFiltered("forbidden", true);
    assertFiltered("replacement", false);
  }

  @Test
  void validReloadPublishesOnlyAfterAllOtherStagesSucceed() {
    newRulesOnReload();
    try (MockedStatic<PluginSettings> settings = mockStatic(PluginSettings.class);
        MockedConstruction<Messages> messages = mockConstruction(Messages.class);
        MockedConstruction<SyncClient> syncs =
            mockConstruction(
                SyncClient.class,
                (created, context) ->
                    doAnswer(
                            call -> {
                              assertFiltered("forbidden", true);
                              assertFiltered("replacement", false);
                              return null;
                            })
                        .when(created)
                        .start())) {
      settings.when(() -> PluginSettings.read(config, directory)).thenReturn(nextSettings);
      plugin.reloadSettings();
      assertEquals(1, syncs.constructed().size());
      assertSame(syncs.constructed().getFirst(), plugin.sync());
      assertSame(nextSettings, plugin.settings());
      assertSame(messages.constructed().getFirst(), plugin.messages());
      verify(previousSync).stop();
      verify(menus).closeAll();
      assertFiltered("forbidden", false);
      assertFiltered("replacement", true);
    }
  }

  @Test
  void laterReloadFailureRestoresConfigurationAndNeverPublishesCandidateRules() {
    String previous = config.saveToString();
    newRulesOnReload();
    try (MockedStatic<PluginSettings> settings = mockStatic(PluginSettings.class);
        MockedConstruction<Messages> messages = mockConstruction(Messages.class);
        MockedConstruction<SyncClient> syncs =
            mockConstruction(
                SyncClient.class,
                (created, context) -> {
                  if (context.getCount() == 1)
                    doThrow(new IllegalStateException("startup refused")).when(created).start();
                })) {
      settings.when(() -> PluginSettings.read(config, directory)).thenReturn(nextSettings);
      assertThrows(IllegalArgumentException.class, plugin::reloadSettings);
      assertEquals(previous, config.saveToString());
      assertSame(previousSettings, plugin.settings());
      assertSame(previousMessages, plugin.messages());
      assertEquals(
          2,
          syncs.constructed().size(),
          "Previous service configuration is restarted after a committed stop");
      verify(syncs.constructed().getFirst()).stop();
      verify(syncs.constructed().getLast()).start();
      assertFiltered("forbidden", true);
      assertFiltered("replacement", false);
    }
  }

  private void newRulesOnReload() {
    doAnswer(
            call -> {
              config.set("chat-filter.banned-words", List.of("replacement"));
              return null;
            })
        .when(plugin)
        .reloadConfig();
  }

  private PluginSettings settings() {
    PluginSettings settings = mock(PluginSettings.class);
    when(settings.serverId()).thenReturn("test-server");
    when(settings.database()).thenReturn(directory.resolve("punishments.db"));
    return settings;
  }

  private void assertFiltered(String text, boolean expected) {
    Component message = Component.text(text);
    Player player = mock(Player.class);
    AsyncChatEvent event =
        new AsyncChatEvent(
            true,
            player,
            Set.of(player),
            mock(ChatRenderer.class),
            message,
            message,
            mock(SignedMessage.class));
    listener.earlyChat(event);
    assertEquals(expected, event.isCancelled());
  }

  private void field(String name, Object value) throws Exception {
    var field = MineplayPunishments.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(plugin, value);
  }
}
