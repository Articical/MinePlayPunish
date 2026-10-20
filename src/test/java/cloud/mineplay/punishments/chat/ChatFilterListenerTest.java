package cloud.mineplay.punishments.chat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cloud.mineplay.punishments.MineplayPunishments;
import cloud.mineplay.punishments.config.Messages;
import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.AbstractSet;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.chat.SignedMessage;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ChatFilterListenerTest {
  private MineplayPunishments plugin;
  private Player player;
  private Messages messages;
  private ChatFilterListener listener;
  private final ArrayDeque<Runnable> mainTasks = new ArrayDeque<>();
  private final Component feedback = Component.text("Message blocked");

  @BeforeEach
  void setup() {
    plugin = mock(MineplayPunishments.class);
    player = mock(Player.class);
    when(player.isOnline()).thenReturn(true);
    messages = mock(Messages.class);
    when(messages.text("chat-filter-blocked")).thenReturn(feedback);
    when(plugin.messages()).thenReturn(messages);
    doAnswer(
            call -> {
              mainTasks.addLast(call.getArgument(0));
              return null;
            })
        .when(plugin)
        .onMain(any(Runnable.class));
    listener = new ChatFilterListener(plugin, filter(true, List.of("forbidden"), List.of()));
  }

  private static ChatFilter filter(boolean enabled, List<String> words, List<String> expressions) {
    YamlConfiguration configuration = new YamlConfiguration();
    configuration.set("chat-filter.enabled", enabled);
    configuration.set("chat-filter.banned-words", words);
    configuration.set("chat-filter.regex-patterns", expressions);
    return ChatFilter.read(configuration);
  }

  private AsyncChatEvent event(Component original, Component current) {
    return new AsyncChatEvent(
        true,
        player,
        new LinkedHashSet<>(List.of(player, mock(Audience.class))),
        mock(ChatRenderer.class),
        current,
        original,
        mock(SignedMessage.class));
  }

  @Test
  void originalMatchCancelsBeforeFormattingAndOnlySchedulesOneFeedback() {
    AsyncChatEvent event = event(Component.text("forbidden"), Component.text("clean rewrite"));
    listener.earlyChat(event);
    listener.lateChat(event);
    assertTrue(event.isCancelled());
    assertEquals(1, mainTasks.size());
    verify(player, never()).sendMessage(any(Component.class));
    verifyNoInteractions(messages);
    mainTasks.removeFirst().run();
    verify(player).sendMessage(feedback);
  }

  @Test
  void currentMessageMatchIsAlsoBlockedAtTheEarlyPass() {
    AsyncChatEvent event = event(Component.text("clean original"), Component.text("forbidden"));
    listener.earlyChat(event);
    assertTrue(event.isCancelled());
    assertEquals(1, mainTasks.size());
  }

  @Test
  void laterRewriteIsRecheckedBeforeMonitorSideEffects() {
    Component original = Component.text("clean original");
    AsyncChatEvent event = event(original, original);
    listener.earlyChat(event);
    assertFalse(event.isCancelled());
    event.message(Component.text("forbidden", NamedTextColor.RED));
    listener.lateChat(event);
    assertTrue(event.isCancelled());
    assertEquals(1, mainTasks.size());
  }

  @Test
  void cleanChatPreservesOriginalCurrentRendererSignatureAndViewers() {
    Component original = Component.text("hello");
    Component current =
        Component.text("hello", NamedTextColor.AQUA).append(Component.text(" everyone"));
    AsyncChatEvent event = event(original, current);
    Set<Audience> viewers = event.viewers();
    Set<Audience> before = Set.copyOf(viewers);
    ChatRenderer renderer = event.renderer();
    SignedMessage signed = event.signedMessage();
    listener.earlyChat(event);
    listener.lateChat(event);
    assertFalse(event.isCancelled());
    assertSame(original, event.originalMessage());
    assertSame(current, event.message());
    assertSame(renderer, event.renderer());
    assertSame(signed, event.signedMessage());
    assertSame(viewers, event.viewers());
    assertEquals(before, event.viewers());
    verifyNoInteractions(renderer);
    assertTrue(mainTasks.isEmpty());
    verify(plugin, never()).getConfig();
    verify(plugin, never()).punishments();
    verify(plugin, never()).getDataFolder();
  }

  @Test
  void cancellationPreservesTheMessageAndViewerSetWithoutIteratingLazyViewers() {
    Set<Audience> lazy =
        new AbstractSet<>() {
          @Override
          public Iterator<Audience> iterator() {
            throw new AssertionError("Viewers must remain lazy");
          }

          @Override
          public int size() {
            throw new AssertionError("Viewers must not be inspected");
          }
        };
    Component body = Component.text("for").append(Component.text("bidden", NamedTextColor.RED));
    ChatRenderer renderer = mock(ChatRenderer.class);
    AsyncChatEvent event =
        new AsyncChatEvent(true, player, lazy, renderer, body, body, mock(SignedMessage.class));
    listener.earlyChat(event);
    assertTrue(event.isCancelled());
    assertSame(body, event.message());
    assertSame(body, event.originalMessage());
    assertSame(lazy, event.viewers());
    assertSame(renderer, event.renderer());
    verifyNoInteractions(renderer);
  }

  @Test
  void preCancelledChatIsNotMatchedOrNotifiedAgain() {
    ChatFilter filter = mock(ChatFilter.class);
    when(filter.active()).thenReturn(true);
    listener.replace(filter);
    AsyncChatEvent event = event(Component.text("forbidden"), Component.text("forbidden"));
    event.setCancelled(true);
    listener.earlyChat(event);
    listener.lateChat(event);
    verify(filter, never()).blocks(anyString());
    assertTrue(mainTasks.isEmpty());
  }

  @Test
  void disabledFilterDoesNotReadOrSerializeTheEvent() {
    listener.replace(filter(false, List.of("forbidden"), List.of()));
    AsyncChatEvent event = mock(AsyncChatEvent.class);
    listener.earlyChat(event);
    listener.lateChat(event);
    verifyNoInteractions(event);
    assertTrue(mainTasks.isEmpty());
  }

  @Test
  void emptyRulesDoNotReadOrSerializeTheEvent() {
    listener.replace(filter(true, List.of(), List.of()));
    AsyncChatEvent event = mock(AsyncChatEvent.class);
    listener.earlyChat(event);
    listener.lateChat(event);
    verifyNoInteractions(event);
    assertTrue(mainTasks.isEmpty());
  }

  @Test
  void permissionAndOperatorStatusDoNotBypassFiltering() {
    when(player.isOp()).thenReturn(true);
    when(player.hasPermission(anyString())).thenReturn(true);
    AsyncChatEvent event = event(Component.text("forbidden"), Component.text("forbidden"));
    listener.earlyChat(event);
    assertTrue(event.isCancelled());
    verify(player, never()).isOp();
    verify(player, never()).hasPermission(anyString());
  }

  @Test
  void asyncMatchingStaysOnTheEventThreadWhileFeedbackUsesOnMain() {
    ChatFilter filter = mock(ChatFilter.class);
    when(filter.active()).thenReturn(true);
    AtomicReference<Thread> matchingThread = new AtomicReference<>();
    when(filter.blocks(anyString()))
        .thenAnswer(
            call -> {
              matchingThread.set(Thread.currentThread());
              return true;
            });
    listener.replace(filter);
    AsyncChatEvent event = event(Component.text("forbidden"), Component.text("forbidden"));
    Thread testThread = Thread.currentThread();
    CompletableFuture.runAsync(() -> listener.earlyChat(event)).join();
    assertTrue(event.isCancelled());
    assertNotNull(matchingThread.get());
    assertNotSame(testThread, matchingThread.get());
    verify(player, never()).isOnline();
    verify(player, never()).sendMessage(any(Component.class));
    verifyNoInteractions(messages);
    assertEquals(1, mainTasks.size());
    mainTasks.removeFirst().run();
    verify(player).sendMessage(feedback);
  }

  @Test
  void synchronousSyntheticChatIsFilteredWithoutDeferringTheDecision() {
    Component body = Component.text("forbidden");
    AsyncChatEvent event =
        new AsyncChatEvent(
            false,
            player,
            Set.of(player),
            mock(ChatRenderer.class),
            body,
            body,
            mock(SignedMessage.class));
    listener.earlyChat(event);
    assertTrue(event.isCancelled());
    assertEquals(1, mainTasks.size());
  }

  @Test
  void queuedFeedbackDoesNotSendToADisconnectedPlayer() {
    AsyncChatEvent event = event(Component.text("forbidden"), Component.text("forbidden"));
    listener.earlyChat(event);
    when(player.isOnline()).thenReturn(false);
    mainTasks.removeFirst().run();
    verify(player, never()).sendMessage(any(Component.class));
    verifyNoInteractions(messages);
  }

  @Test
  void publishedReplacementChangesRulesAndNullReplacementKeepsThePreviousSnapshot() {
    listener.replace(filter(true, List.of("replacement"), List.of()));
    assertThrows(NullPointerException.class, () -> listener.replace(null));
    AsyncChatEvent old = event(Component.text("forbidden"), Component.text("forbidden"));
    listener.earlyChat(old);
    assertFalse(old.isCancelled());
    AsyncChatEvent replacement =
        event(Component.text("replacement"), Component.text("replacement"));
    listener.earlyChat(replacement);
    assertTrue(replacement.isCancelled());
  }

  @Test
  void aPassUsesOneSnapshotEvenWhenReplacementOccursDuringMatching() {
    ChatFilter original = mock(ChatFilter.class);
    ChatFilter replacement = mock(ChatFilter.class);
    when(original.active()).thenReturn(true);
    when(original.blocks("original"))
        .thenAnswer(
            call -> {
              listener.replace(replacement);
              return false;
            });
    when(original.blocks("current")).thenReturn(true);
    listener.replace(original);
    AsyncChatEvent event = event(Component.text("original"), Component.text("current"));
    listener.earlyChat(event);
    assertTrue(event.isCancelled());
    verify(original).blocks("current");
    verifyNoInteractions(replacement);
  }

  @Test
  void onlyNativePublicChatHandlersAreRegisteredWithCancellationChecks() throws Exception {
    EventHandler early =
        ChatFilterListener.class
            .getMethod("earlyChat", AsyncChatEvent.class)
            .getAnnotation(EventHandler.class);
    EventHandler late =
        ChatFilterListener.class
            .getMethod("lateChat", AsyncChatEvent.class)
            .getAnnotation(EventHandler.class);
    assertEquals(EventPriority.LOWEST, early.priority());
    assertEquals(EventPriority.HIGHEST, late.priority());
    assertTrue(early.ignoreCancelled());
    assertTrue(late.ignoreCancelled());
    assertEquals(
        2,
        java.util.Arrays.stream(ChatFilterListener.class.getDeclaredMethods())
            .filter(method -> method.isAnnotationPresent(EventHandler.class))
            .count());
  }
}
