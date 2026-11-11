package cloud.mineplay.punishments.afk;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cloud.mineplay.punishments.config.Messages;
import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import net.kyori.adventure.chat.SignedMessage;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class AfkManagerTest {
  private static final AfkSettings SETTINGS = new AfkSettings(true, 60, 10, 5);
  private final AtomicLong clock = new AtomicLong();
  private final AtomicInteger codes = new AtomicInteger();
  private final Map<UUID, Player> current = new HashMap<>();
  private final List<Player> online = new ArrayList<>();
  private MockedStatic<Bukkit> bukkit;
  private MockedStatic<HandlerList> handlers;
  private JavaPlugin plugin;
  private Server server;
  private PluginManager pluginManager;
  private BukkitScheduler scheduler;
  private BukkitTask task;
  private PluginCommand command;
  private Logger logger;
  private World world;
  private AfkManager manager;
  private Connected alice;

  private static final class Connected {
    final Player player = mock(Player.class);
    final Input input = mock(Input.class);
    final AtomicReference<Location> location = new AtomicReference<>();
    final AtomicBoolean bypass = new AtomicBoolean();
    final List<Component> messages = new ArrayList<>();
    final AtomicInteger kicks = new AtomicInteger();

    List<Component> warnings() {
      return messages.stream().filter(message -> message.clickEvent() != null).toList();
    }

    String code() {
      String value =
          ((ClickEvent.Payload.Text) warnings().getLast().clickEvent().payload()).value();
      return value.substring(value.lastIndexOf(' ') + 1);
    }
  }

  @BeforeEach
  void setup() {
    bukkit = mockStatic(Bukkit.class);
    bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
    handlers = mockStatic(HandlerList.class);
    plugin = mock(JavaPlugin.class);
    server = mock(Server.class);
    pluginManager = mock(PluginManager.class);
    scheduler = mock(BukkitScheduler.class);
    task = mock(BukkitTask.class);
    command = mock(PluginCommand.class);
    logger = mock(Logger.class);
    world = mock(World.class);
    when(world.getUID()).thenReturn(UUID.randomUUID());
    when(plugin.getServer()).thenReturn(server);
    when(plugin.getCommand("mpactive")).thenReturn(command);
    when(plugin.getLogger()).thenReturn(logger);
    when(server.getPluginManager()).thenReturn(pluginManager);
    when(server.getScheduler()).thenReturn(scheduler);
    doReturn(online).when(server).getOnlinePlayers();
    when(server.getPlayer(any(UUID.class))).thenAnswer(call -> current.get(call.getArgument(0)));
    when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), anyLong(), anyLong()))
        .thenReturn(task);
    Messages messages = mock(Messages.class);
    when(messages.text(anyString(), any(String[].class)))
        .thenAnswer(call -> Component.text(call.getArgument(0, String.class)));
    alice = connect(UUID.randomUUID());
    manager =
        new AfkManager(
            plugin,
            () -> messages,
            SETTINGS,
            clock::get,
            () -> String.format("%08X", codes.incrementAndGet()));
    manager.start();
  }

  @AfterEach
  void teardown() {
    if (manager != null) manager.stop();
    handlers.close();
    bukkit.close();
  }

  private Connected connect(UUID id) {
    Connected result = new Connected();
    result.location.set(new Location(world, 0, 64, 0));
    when(result.player.getUniqueId()).thenReturn(id);
    when(result.player.isOnline()).thenReturn(true);
    when(result.player.getCurrentInput()).thenReturn(result.input);
    when(result.player.getLocation()).thenAnswer(call -> result.location.get());
    when(result.player.hasPermission("mineplaypunishments.afk.bypass"))
        .thenAnswer(call -> result.bypass.get());
    doAnswer(
            call -> {
              result.messages.add(call.getArgument(0));
              return null;
            })
        .when(result.player)
        .sendMessage(any(Component.class));
    doAnswer(
            call -> {
              result.kicks.incrementAndGet();
              return null;
            })
        .when(result.player)
        .kick(any(Component.class));
    current.put(id, result.player);
    online.add(result.player);
    return result;
  }

  private void at(long seconds) {
    clock.set(TimeUnit.SECONDS.toNanos(seconds));
    manager.tick();
  }

  private void join(Connected player) {
    PlayerJoinEvent event = mock(PlayerJoinEvent.class);
    when(event.getPlayer()).thenReturn(player.player);
    manager.join(event);
  }

  private void quit(Connected player) {
    PlayerQuitEvent event = mock(PlayerQuitEvent.class);
    when(event.getPlayer()).thenReturn(player.player);
    manager.quit(event);
  }

  private void breakBlock(int x, boolean cancelled) {
    Block block = mock(Block.class);
    when(block.getLocation()).thenReturn(new Location(world, x, 64, 0));
    BlockBreakEvent event = mock(BlockBreakEvent.class);
    when(event.getPlayer()).thenReturn(alice.player);
    when(event.getBlock()).thenReturn(block);
    when(event.isCancelled()).thenReturn(cancelled);
    manager.breakBlock(event);
  }

  private void placeBlock(int x, boolean cancelled) {
    Block block = mock(Block.class);
    when(block.getLocation()).thenReturn(new Location(world, x, 64, 0));
    BlockPlaceEvent event = mock(BlockPlaceEvent.class);
    when(event.getPlayer()).thenReturn(alice.player);
    when(event.getBlockPlaced()).thenReturn(block);
    when(event.isCancelled()).thenReturn(cancelled);
    manager.placeBlock(event);
  }

  private AsyncChatEvent chat(Connected player, String original, String currentMessage) {
    return new AsyncChatEvent(
        true,
        player.player,
        Set.of(player.player),
        mock(ChatRenderer.class),
        Component.text(currentMessage),
        Component.text(original),
        mock(SignedMessage.class));
  }

  private void confirm(Connected player, String... args) {
    assertTrue(manager.onCommand(player.player, command, "mpactive", args));
  }

  @Test
  void oneSparseTaskWarnsOnceThenUsesAnOrdinaryKick() {
    manager.start();
    verify(scheduler).runTaskTimer(eq(plugin), any(Runnable.class), eq(100L), eq(100L));
    verify(pluginManager).registerEvents(manager, plugin);
    verify(command).setExecutor(manager);
    verify(command).setTabCompleter(manager);
    at(49);
    assertTrue(alice.messages.isEmpty());
    at(50);
    assertEquals(1, alice.warnings().size());
    assertEquals(ClickEvent.Action.RUN_COMMAND, alice.warnings().getFirst().clickEvent().action());
    assertEquals(
        ClickEvent.runCommand("/mineplaypunishments:mpactive 00000001"),
        alice.warnings().getFirst().clickEvent());
    at(55);
    assertEquals(1, alice.warnings().size());
    assertEquals(0, alice.kicks.get());
    at(60);
    assertEquals(1, alice.kicks.get());
    verify(alice.player).kick(Component.text("anti-afk-kick"));
  }

  @Test
  void manualMovementToANewCellCountsButPhysicsAndVehiclesDoNot() {
    at(40);
    alice.location.set(new Location(world, 8, 70, 0));
    at(45); // A current moves the player, without manual input.
    when(alice.input.isForward()).thenReturn(true);
    when(alice.player.isInsideVehicle()).thenReturn(true);
    alice.location.set(new Location(world, 16, 70, 0));
    at(50);
    assertEquals(1, alice.warnings().size());
    when(alice.player.isInsideVehicle()).thenReturn(false);
    alice.location.set(new Location(world, 24, 70, 0));
    at(55);
    at(104);
    assertEquals(1, alice.warnings().size());
    assertEquals(0, alice.kicks.get());
    at(105);
    assertEquals(2, alice.warnings().size());
  }

  @Test
  void cancelledBlocksNeverCountAndBreakPlaceCyclesDoNotRefreshIdle() {
    at(40);
    breakBlock(1, true);
    placeBlock(2, true);
    at(50);
    assertEquals(1, alice.warnings().size());
    clock.set(TimeUnit.SECONDS.toNanos(55));
    breakBlock(3, false);
    at(95);
    placeBlock(3, false); // Same coordinates cannot perpetually reset idle.
    at(105);
    assertEquals(2, alice.warnings().size());
    placeBlock(4, false);
    at(115);
    assertEquals(0, alice.kicks.get());
  }

  @Test
  void teleportsRespawnsAndWorldChangesOnlyInvalidateMovementBaselines() {
    when(alice.input.isForward()).thenReturn(true);
    PlayerTeleportEvent teleport = mock(PlayerTeleportEvent.class);
    when(teleport.getPlayer()).thenReturn(alice.player);
    at(30);
    manager.teleport(teleport);
    alice.location.set(new Location(world, 1000, 64, 0));
    at(35);
    PlayerRespawnEvent respawn = mock(PlayerRespawnEvent.class);
    when(respawn.getPlayer()).thenReturn(alice.player);
    manager.respawn(respawn);
    alice.location.set(new Location(world, 2000, 64, 0));
    at(40);
    PlayerChangedWorldEvent changed = mock(PlayerChangedWorldEvent.class);
    when(changed.getPlayer()).thenReturn(alice.player);
    manager.changedWorld(changed);
    alice.location.set(new Location(world, 3000, 64, 0));
    at(45);
    at(50);
    assertEquals(1, alice.warnings().size());
  }

  @Test
  void warningCodeIsRequiredSingleUseAndBoundToItsConnection() {
    at(50);
    String oldCode = alice.code();
    confirm(alice);
    confirm(alice, "wrong");
    confirm(alice, oldCode, "extra");
    assertEquals(0, alice.kicks.get());
    assertEquals(
        3, alice.messages.stream().filter(Component.text("anti-afk-invalid")::equals).count());
    at(55);
    confirm(alice, oldCode.toLowerCase());
    assertTrue(alice.messages.contains(Component.text("anti-afk-confirmed")));
    confirm(alice, oldCode);
    at(104);
    assertEquals(1, alice.warnings().size());
    at(105);
    assertEquals(2, alice.warnings().size());
    assertNotEquals(oldCode, alice.code());
    confirm(alice, oldCode);
    at(115);
    assertEquals(1, alice.kicks.get());
    assertTrue(
        manager.onTabComplete(alice.player, command, "mpactive", new String[] {""}).isEmpty());
  }

  @Test
  void consoleCannotUseAPlayersWarningCode() {
    at(50);
    CommandSender console = mock(CommandSender.class);
    manager.onCommand(console, command, "mpactive", new String[] {alice.code()});
    verify(console).sendMessage(Component.text("error"));
    at(60);
    assertEquals(1, alice.kicks.get());
  }

  @Test
  void dynamicBypassGrantSuppressesKickAndRevocationGetsFreshGrace() {
    at(50);
    alice.bypass.set(true);
    at(60);
    assertEquals(0, alice.kicks.get());
    alice.bypass.set(false);
    at(100);
    at(149);
    assertEquals(1, alice.warnings().size());
    at(150);
    assertEquals(2, alice.warnings().size());
    at(160);
    assertEquals(1, alice.kicks.get());
  }

  @Test
  void commandsAlsoRecheckNewlyGrantedBypass() {
    at(50);
    alice.bypass.set(true);
    confirm(alice, alice.code());
    assertFalse(alice.messages.contains(Component.text("anti-afk-confirmed")));
    at(60);
    assertEquals(0, alice.kicks.get());
  }

  @Test
  void originalDistinctChatIsCoalescedOffThreadWithoutSchedulingTasks() throws Exception {
    at(40);
    AsyncChatEvent first = chat(alice, "Hello everyone", "rewritten timestamp one");
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread thread =
        new Thread(
            () -> {
              try {
                manager.chat(first);
              } catch (Throwable error) {
                failure.set(error);
              }
            });
    thread.start();
    thread.join(5000);
    assertFalse(thread.isAlive());
    assertNull(failure.get());
    at(45);
    at(85);
    manager.chat(chat(alice, "hELLo  everyone", "rewritten timestamp two"));
    at(90);
    at(95);
    assertEquals(1, alice.warnings().size());
    manager.chat(chat(alice, "Anyone exploring tonight?", "rewritten timestamp three"));
    at(100);
    at(105);
    assertEquals(0, alice.kicks.get());
    verify(scheduler).runTaskTimer(eq(plugin), any(Runnable.class), eq(100L), eq(100L));
    verifyNoMoreInteractions(scheduler);
  }

  @Test
  void cancelledBlankAndOversizedChatCannotPreventRemoval() {
    at(40);
    AsyncChatEvent cancelled = chat(alice, "new cancelled words", "new cancelled words");
    cancelled.setCancelled(true);
    manager.chat(cancelled);
    manager.chat(chat(alice, " \t ", "rewritten"));
    manager.chat(chat(alice, "x".repeat(4097), "rewritten"));
    at(50);
    assertEquals(1, alice.warnings().size());
    at(60);
    assertEquals(1, alice.kicks.get());
  }

  @Test
  void oldChatQuitAndCodeCannotAffectAReconnectedPlayer() {
    at(50);
    String oldCode = alice.code();
    Connected replacement = connect(alice.player.getUniqueId());
    join(replacement);
    quit(alice);
    at(90);
    manager.chat(chat(alice, "late message from old connection", "late"));
    confirm(alice, oldCode);
    confirm(replacement, oldCode);
    at(100);
    assertEquals(1, replacement.warnings().size());
    assertNotEquals(oldCode, replacement.code());
    at(110);
    assertEquals(1, replacement.kicks.get());
    assertEquals(0, alice.kicks.get());
  }

  @Test
  void pendingOldChatIsDiscardedOnReconnect() {
    at(40);
    manager.chat(chat(alice, "waiting to be drained", "waiting"));
    Connected replacement = connect(alice.player.getUniqueId());
    join(replacement);
    at(90);
    assertEquals(1, replacement.warnings().size());
    assertEquals(0, alice.warnings().size());
  }

  @Test
  void reloadPreservesIdleAndOutstandingWarningGrace() {
    at(50);
    String code = alice.code();
    manager.reconfigure(new AfkSettings(true, 60, 5, 1));
    verify(task).cancel();
    verify(scheduler).runTaskTimer(eq(plugin), any(Runnable.class), eq(20L), eq(20L));
    at(56);
    assertEquals(0, alice.kicks.get());
    assertEquals(code, alice.code());
    at(60);
    assertEquals(1, alice.kicks.get());
  }

  @Test
  void disablingAndReenablingStartsAFreshGraceAndStopClearsState() {
    at(50);
    manager.reconfigure(new AfkSettings(false, 60, 10, 5));
    at(100);
    manager.reconfigure(SETTINGS);
    at(149);
    assertEquals(1, alice.warnings().size());
    at(150);
    assertEquals(2, alice.warnings().size());
    manager.stop();
    at(1000);
    manager.chat(chat(alice, "after stop", "after stop"));
    join(alice);
    assertEquals(0, alice.kicks.get());
    handlers.verify(() -> HandlerList.unregisterAll(manager));
  }

  @Test
  void cancelledIdleKickWarnsAgainAndWaitsTheFullGrace() {
    doAnswer(
            call -> {
              alice.kicks.incrementAndGet();
              PlayerKickEvent event = mock(PlayerKickEvent.class);
              when(event.getPlayer()).thenReturn(alice.player);
              when(event.isCancelled()).thenReturn(true);
              manager.kick(event);
              return null;
            })
        .when(alice.player)
        .kick(any(Component.class));
    at(50);
    at(60);
    at(65);
    assertEquals(2, alice.warnings().size());
    assertEquals(1, alice.kicks.get());
    at(70);
    assertEquals(1, alice.kicks.get());
    at(75);
    assertEquals(2, alice.kicks.get());
  }

  @Test
  void unrelatedCancelledKickDoesNotRefreshIdleOrClearWarning() {
    at(50);
    PlayerKickEvent event = mock(PlayerKickEvent.class);
    when(event.getPlayer()).thenReturn(alice.player);
    when(event.isCancelled()).thenReturn(true);
    manager.kick(event);
    at(60);
    assertEquals(1, alice.kicks.get());
  }

  @Test
  void failedKickIsRetriedAfterGraceWithOnlyOneControlledLog() {
    doThrow(new IllegalStateException("untrusted callback detail"))
        .when(alice.player)
        .kick(any(Component.class));
    at(50);
    at(60);
    at(65);
    at(75);
    verify(logger).warning("event=anti_afk_kick_failed detail=SERVER_CALLBACK_FAILURE");
    verifyNoMoreInteractions(logger);
    assertEquals(2, alice.warnings().size());
  }
}
