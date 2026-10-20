package cloud.mineplay.punishments.gui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cloud.mineplay.punishments.MineplayPunishments;
import cloud.mineplay.punishments.commands.StaffModeration;
import cloud.mineplay.punishments.commands.StaffModeration.Resolution;
import cloud.mineplay.punishments.commands.StaffModeration.Target;
import cloud.mineplay.punishments.communityservice.CommunityServiceManager;
import cloud.mineplay.punishments.config.Messages;
import cloud.mineplay.punishments.config.PluginSettings;
import cloud.mineplay.punishments.punishment.Models.Result;
import cloud.mineplay.punishments.punishment.Models.Type;
import cloud.mineplay.punishments.punishment.PunishmentService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.stream.Stream;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

/** Controller tests keep UI transitions queued until an explicit simulated server tick. */
class PunishMenuTest {
  private MineplayPunishments plugin;
  private StaffModeration gateway;
  private PunishMenu.View view;
  private PunishmentService service;
  private CommunityServiceManager community;
  private PluginSettings settings;
  private YamlConfiguration config;
  private PunishMenu menu;
  private Player player;
  private Target target;
  private Inventory currentTop;
  private MutableClock clock;
  private MockedStatic<Bukkit> bukkit;
  private final ArrayDeque<Runnable> scheduled = new ArrayDeque<>();
  private final List<ShownMenu> menus = new ArrayList<>();
  private final List<ReasonPrompt> prompts = new ArrayList<>();
  private final List<Submission> submissions = new ArrayList<>();
  private final List<ServicePrompt> servicePrompts = new ArrayList<>();
  private final List<ServiceSubmission> serviceSubmissions = new ArrayList<>();

  record ShownMenu(
      Inventory inventory, UUID sessionId, Target target, List<PunishMenu.Button> buttons) {}

  record ReasonPrompt(
      Player actor,
      Target target,
      Type type,
      DurationPreset preset,
      int maxLength,
      BiConsumer<Player, String> confirm,
      Consumer<Player> cancel) {}

  record Submission(
      Player actor,
      UUID target,
      UUID actionId,
      Type type,
      Duration duration,
      String reason,
      boolean silent,
      BooleanSupplier guard,
      CompletableFuture<Result> completion) {}

  record ServicePrompt(
      Player actor,
      Target target,
      Integer presetBlocks,
      int minimum,
      int maximum,
      int maxReason,
      BiConsumer<Player, PunishMenu.ServiceInput> confirm,
      Consumer<Player> cancel) {}

  record ServiceSubmission(
      Player actor,
      UUID target,
      UUID actionId,
      int blocks,
      String reason,
      boolean silent,
      BooleanSupplier guard,
      CompletableFuture<Result> completion) {}

  @BeforeEach
  void setup() {
    plugin = mock(MineplayPunishments.class);
    gateway = mock(StaffModeration.class);
    view = mock(PunishMenu.View.class);
    service = mock(PunishmentService.class);
    community = mock(CommunityServiceManager.class);
    player = mock(Player.class);
    when(player.getUniqueId()).thenReturn(UUID.randomUUID());
    when(player.isOnline()).thenReturn(true);
    when(player.hasPermission("mineplaypunishments.gui")).thenReturn(true);
    target = new Target(UUID.randomUUID(), "Target");
    when(plugin.punishments()).thenReturn(service);
    when(service.healthy()).thenReturn(true);
    when(plugin.community()).thenReturn(community);
    settings = mock(PluginSettings.class);
    when(settings.maximumReasonLength()).thenReturn(1000);
    when(settings.minimumBlocks()).thenReturn(10);
    when(settings.maximumBlocks()).thenReturn(10000);
    when(plugin.settings()).thenReturn(settings);
    config = new YamlConfiguration();
    when(plugin.getConfig()).thenReturn(config);
    Messages messages = mock(Messages.class);
    when(messages.text(eq("error"), eq("message"), anyString()))
        .thenReturn(Component.text("Error"));
    when(plugin.messages()).thenReturn(messages);
    doAnswer(
            call -> {
              call.<Runnable>getArgument(0).run();
              return null;
            })
        .when(plugin)
        .onMain(any(Runnable.class));
    InventoryView openView = mock(InventoryView.class);
    currentTop = mock(Inventory.class);
    when(player.getOpenInventory()).thenReturn(openView);
    when(openView.getTopInventory()).thenAnswer(call -> currentTop);
    doAnswer(
            call -> {
              currentTop = mock(Inventory.class);
              return null;
            })
        .when(player)
        .closeInventory();
    when(gateway.resolve(eq(player), anyString()))
        .thenReturn(
            CompletableFuture.completedFuture(
                new Resolution(target, "RESOLVED", "Target verified")));
    when(gateway.available(eq(player), any(Type.class), nullable(Duration.class))).thenReturn(true);
    when(gateway.availableService(player)).thenReturn(true);
    when(view.open(eq(player), any(UUID.class), any(Target.class), anyString(), anyList()))
        .thenAnswer(
            call -> {
              Inventory inventory = mock(Inventory.class);
              UUID nonce = call.getArgument(1);
              PaperMenuView.Holder holder = new PaperMenuView.Holder(player.getUniqueId(), nonce);
              when(inventory.getHolder()).thenReturn(holder);
              currentTop = inventory;
              List<PunishMenu.Button> buttons = call.getArgument(4);
              menus.add(new ShownMenu(inventory, nonce, call.getArgument(2), List.copyOf(buttons)));
              return inventory;
            });
    doAnswer(
            call -> {
              prompts.add(
                  new ReasonPrompt(
                      call.getArgument(0),
                      call.getArgument(1),
                      call.getArgument(2),
                      call.getArgument(3),
                      call.getArgument(4),
                      call.getArgument(5),
                      call.getArgument(6)));
              return null;
            })
        .when(view)
        .reason(
            any(Player.class),
            any(Target.class),
            any(Type.class),
            nullable(DurationPreset.class),
            anyInt(),
            any(),
            any());
    doAnswer(
            call -> {
              servicePrompts.add(
                  new ServicePrompt(
                      call.getArgument(0),
                      call.getArgument(1),
                      call.getArgument(2),
                      call.getArgument(3),
                      call.getArgument(4),
                      call.getArgument(5),
                      call.getArgument(6),
                      call.getArgument(7)));
              return null;
            })
        .when(view)
        .serviceReason(
            any(Player.class),
            any(Target.class),
            nullable(Integer.class),
            anyInt(),
            anyInt(),
            anyInt(),
            any(),
            any());
    when(gateway.submit(
            any(Player.class),
            any(UUID.class),
            any(UUID.class),
            any(Type.class),
            nullable(Duration.class),
            nullable(String.class),
            anyBoolean(),
            any(BooleanSupplier.class)))
        .thenAnswer(
            call -> {
              CompletableFuture<Result> result = new CompletableFuture<>();
              submissions.add(
                  new Submission(
                      call.getArgument(0),
                      call.getArgument(1),
                      call.getArgument(2),
                      call.getArgument(3),
                      call.getArgument(4),
                      call.getArgument(5),
                      call.getArgument(6),
                      call.getArgument(7),
                      result));
              return result;
            });
    when(gateway.submitService(
            any(Player.class),
            any(UUID.class),
            any(UUID.class),
            anyInt(),
            nullable(String.class),
            anyBoolean(),
            any(BooleanSupplier.class)))
        .thenAnswer(
            call -> {
              CompletableFuture<Result> result = new CompletableFuture<>();
              serviceSubmissions.add(
                  new ServiceSubmission(
                      call.getArgument(0),
                      call.getArgument(1),
                      call.getArgument(2),
                      call.getArgument(3),
                      call.getArgument(4),
                      call.getArgument(5),
                      call.getArgument(6),
                      result));
              return result;
            });
    BukkitScheduler scheduler = mock(BukkitScheduler.class);
    BukkitTask task = mock(BukkitTask.class);
    when(scheduler.runTask(eq(plugin), any(Runnable.class)))
        .thenAnswer(
            call -> {
              scheduled.addLast(call.getArgument(1));
              return task;
            });
    bukkit = mockStatic(Bukkit.class);
    bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
    clock = new MutableClock();
    menu = new PunishMenu(plugin, gateway, view, clock);
  }

  @Test
  void silentMenuKeepsItsFlagThroughWarningAndServiceConfirmation() {
    menu.onCommand(player, null, "punish", new String[] {"Target", "-s"});
    assertEquals(
        "Silent punishment",
        shown().buttons().stream().filter(b -> b.slot() == 24).findFirst().orElseThrow().label());
    click(currentTop, player, 16, ClickType.LEFT, false);
    while (!scheduled.isEmpty()) tick();
    prompts.getLast().confirm().accept(player, "Test warning");
    assertTrue(submissions.getLast().silent());
    menu.onCommand(player, null, "punish", new String[] {"--silent", "Target"});
    click(currentTop, player, 22, ClickType.LEFT, false);
    while (!scheduled.isEmpty()) tick();
    click(currentTop, player, 10, ClickType.LEFT, false);
    while (!scheduled.isEmpty()) tick();
    servicePrompts
        .getLast()
        .confirm()
        .accept(player, new PunishMenu.ServiceInput(null, "Test service"));
    assertTrue(serviceSubmissions.getLast().silent());
  }

  @Test
  void disabledServiceButtonShowsPermissionFailureRatherThanQuarrySetupClaim() {
    when(gateway.availableService(player)).thenReturn(false);
    when(gateway.serviceUnavailableReason(player))
        .thenReturn("You do not have permission to issue Community Service");
    open();
    assertEquals(
        "You do not have permission to issue Community Service",
        shown().buttons().stream().filter(b -> b.slot() == 22).findFirst().orElseThrow().detail());
  }

  @AfterEach
  void cleanup() {
    if (bukkit != null) bukkit.close();
  }

  private void open() {
    assertTrue(menu.onCommand(player, null, "punish", new String[] {"Target"}));
  }

  private ShownMenu shown() {
    return menus.getLast();
  }

  private void tick() {
    int pending = scheduled.size();
    for (int i = 0; i < pending; i++) scheduled.removeFirst().run();
  }

  private InventoryClickEvent click(
      Inventory inventory, Player actor, int slot, ClickType click, boolean cancelled) {
    InventoryView inventoryView = mock(InventoryView.class);
    when(inventoryView.getTopInventory()).thenReturn(inventory);
    InventoryClickEvent event = mock(InventoryClickEvent.class);
    when(event.getView()).thenReturn(inventoryView);
    when(event.getWhoClicked()).thenReturn(actor);
    when(event.getRawSlot()).thenReturn(slot);
    when(event.getClick()).thenReturn(click);
    when(event.isCancelled()).thenReturn(cancelled);
    menu.click(event);
    return event;
  }

  private void choose(PunishMenu.Choice choice) {
    int slot =
        shown().buttons().stream()
            .filter(button -> choice.equals(button.choice()))
            .findFirst()
            .orElseThrow()
            .slot();
    click(currentTop, player, slot, ClickType.LEFT, false);
  }

  private ReasonPrompt reason(Type type, DurationPreset preset) {
    open();
    choose(new PunishMenu.Choice(type, null));
    tick();
    if (type == Type.BAN || type == Type.MUTE) {
      choose(new PunishMenu.Choice(type, preset));
      tick();
    }
    assertTrue(prompts.isEmpty(), "Reason dialog must wait until after the inventory transition");
    tick();
    assertEquals(1, prompts.size());
    return prompts.getFirst();
  }

  private void closeInventory(Inventory inventory) {
    InventoryCloseEvent close = mock(InventoryCloseEvent.class);
    when(close.getPlayer()).thenReturn(player);
    when(close.getInventory()).thenReturn(inventory);
    menu.close(close);
    currentTop = mock(Inventory.class);
  }

  private void serviceBlocks() {
    open();
    choose(new PunishMenu.Choice(Type.COMMUNITY_SERVICE, null));
    tick();
  }

  private ServicePrompt serviceReason(Integer presetBlocks) {
    int previousPrompts = servicePrompts.size();
    serviceBlocks();
    choose(
        presetBlocks == null
            ? PunishMenu.Choice.CUSTOM_SERVICE
            : new PunishMenu.Choice(Type.COMMUNITY_SERVICE, null, null, presetBlocks));
    tick();
    assertEquals(
        previousPrompts, servicePrompts.size(), "Dialog must follow the inventory transition");
    tick();
    assertEquals(previousPrompts + 1, servicePrompts.size());
    return servicePrompts.getLast();
  }

  static Stream<Arguments> timedPresets() {
    return Stream.of(Type.BAN, Type.MUTE)
        .flatMap(
            type ->
                Arrays.stream(DurationPreset.values()).map(preset -> Arguments.of(type, preset)));
  }

  @ParameterizedTest
  @MethodSource("timedPresets")
  void timedSelectionsReachGatewayOnlyAfterExplicitConfirmation(Type type, DurationPreset preset) {
    ReasonPrompt prompt = reason(type, preset);
    assertEquals(type, prompt.type());
    assertEquals(preset, prompt.preset());
    assertEquals(target, prompt.target());
    assertEquals(1000, prompt.maxLength());
    assertTrue(submissions.isEmpty());
    prompt.confirm().accept(player, "Verified reason <red> with quotes \"kept\"");
    assertEquals(1, submissions.size());
    Submission submission = submissions.getFirst();
    assertSame(player, submission.actor());
    assertEquals(target.id(), submission.target());
    assertEquals(type, submission.type());
    assertEquals(preset.duration(), submission.duration());
    assertEquals("Verified reason <red> with quotes \"kept\"", submission.reason());
    assertNotNull(submission.actionId());
    assertTrue(submission.guard().getAsBoolean());
    verify(service, never()).apply(any());
  }

  @ParameterizedTest
  @EnumSource(
      value = Type.class,
      names = {"KICK", "WARNING"})
  void kickAndWarningSkipDurationButStillRequireReasonConfirmation(Type type) {
    ReasonPrompt prompt = reason(type, null);
    assertEquals(1, menus.size());
    assertNull(prompt.preset());
    assertTrue(submissions.isEmpty());
    prompt.confirm().accept(player, "A required reason");
    assertEquals(1, submissions.size());
    assertEquals(type, submissions.getFirst().type());
    assertNull(submissions.getFirst().duration());
  }

  @Test
  void menuTransitionsAreDeferredAndOnlyOneClickCanBePending() {
    open();
    choose(new PunishMenu.Choice(Type.BAN, null));
    choose(new PunishMenu.Choice(Type.MUTE, null));
    assertEquals(1, menus.size());
    assertEquals(1, scheduled.size());
    assertTrue(prompts.isEmpty());
    tick();
    assertEquals(2, menus.size());
    assertEquals(
        DurationPreset.values().length,
        shown().buttons().stream()
            .filter(button -> button.choice() != null && button.choice().type() == Type.BAN)
            .count());
    assertTrue(submissions.isEmpty());
  }

  @Test
  void monthPresetUsesThirtyDaysWhileCommandMMeansOneMinute() {
    assertEquals(Duration.ofDays(30), DurationPreset.MONTH.duration());
    assertEquals(
        Duration.ofMinutes(1), cloud.mineplay.punishments.commands.Arguments.duration("1m"));
    assertEquals(Duration.ofHours(1), DurationPreset.HOUR.duration());
    assertEquals(Duration.ofHours(4), DurationPreset.FOUR_HOURS.duration());
    assertEquals(Duration.ofHours(8), DurationPreset.EIGHT_HOURS.duration());
    assertEquals(Duration.ofDays(1), DurationPreset.DAY.duration());
    assertEquals(Duration.ofDays(7), DurationPreset.WEEK.duration());
    assertNull(DurationPreset.PERMANENT.duration());
  }

  @Test
  void backReturnsToTypeMenuAndCancelDiscardsWithoutSubmitting() {
    open();
    choose(new PunishMenu.Choice(Type.BAN, null));
    tick();
    choose(PunishMenu.Choice.BACK);
    tick();
    assertEquals(3, menus.size());
    assertTrue(
        shown().buttons().stream()
            .anyMatch(button -> new PunishMenu.Choice(Type.WARNING, null).equals(button.choice())));
    choose(PunishMenu.Choice.CANCEL);
    tick();
    assertTrue(prompts.isEmpty());
    assertTrue(submissions.isEmpty());
    Inventory old = shown().inventory();
    click(old, player, 10, ClickType.LEFT, false);
    assertTrue(scheduled.isEmpty());
  }

  @ParameterizedTest
  @EnumSource(
      value = ClickType.class,
      mode = EnumSource.Mode.EXCLUDE,
      names = {"LEFT"})
  void nonLeftInventoryActionsCannotSelectOrMoveItems(ClickType click) {
    open();
    InventoryClickEvent event = click(currentTop, player, 10, click, false);
    verify(event).setCancelled(true);
    assertTrue(scheduled.isEmpty());
    assertTrue(submissions.isEmpty());
  }

  @Test
  void cancelledBottomAndDecorativeClicksCannotAdvance() {
    open();
    for (int slot : new int[] {4, 27, 35, -999}) {
      InventoryClickEvent event = click(currentTop, player, slot, ClickType.LEFT, false);
      verify(event).setCancelled(true);
    }
    InventoryClickEvent cancelled = click(currentTop, player, 10, ClickType.LEFT, true);
    verify(cancelled).setCancelled(true);
    assertTrue(scheduled.isEmpty());
    assertTrue(submissions.isEmpty());
  }

  @Test
  void inventoryDragsAreCancelledWithoutCreatingAnAction() {
    open();
    InventoryView inventoryView = mock(InventoryView.class);
    when(inventoryView.getTopInventory()).thenReturn(currentTop);
    InventoryDragEvent event = mock(InventoryDragEvent.class);
    when(event.getView()).thenReturn(inventoryView);
    menu.drag(event);
    verify(event).setCancelled(true);
    assertTrue(scheduled.isEmpty());
    assertTrue(submissions.isEmpty());
  }

  @Test
  void closingBeforeScheduledTickCancelsPendingTransition() {
    open();
    choose(new PunishMenu.Choice(Type.BAN, null));
    closeInventory(currentTop);
    tick();
    assertEquals(1, menus.size());
    assertTrue(prompts.isEmpty());
    assertTrue(submissions.isEmpty());
  }

  @Test
  void wrongPlayerCannotUseInventoryOrDialogCallbacksEvenWithSameUuid() {
    open();
    Player impostor = mock(Player.class);
    UUID ownerId = player.getUniqueId();
    when(impostor.getUniqueId()).thenReturn(ownerId);
    InventoryClickEvent event = click(currentTop, impostor, 10, ClickType.LEFT, false);
    verify(event).setCancelled(true);
    assertTrue(scheduled.isEmpty());
    choose(new PunishMenu.Choice(Type.KICK, null));
    tick();
    tick();
    ReasonPrompt prompt = prompts.getFirst();
    prompt.cancel().accept(impostor);
    prompt.confirm().accept(impostor, "Stolen callback");
    assertTrue(submissions.isEmpty());
    prompt.confirm().accept(player, "Actual staff confirmation");
    assertEquals(1, submissions.size());
  }

  @Test
  void duplicateConfirmationIsConsumedBeforeAsyncSubmissionAndAfterCompletion() {
    ReasonPrompt prompt = reason(Type.WARNING, null);
    prompt.confirm().accept(player, "One warning");
    prompt.confirm().accept(player, "One warning");
    assertEquals(1, submissions.size());
    submissions.getFirst().completion().complete(new Result(true, "APPLIED", "Applied", null));
    prompt.confirm().accept(player, "One warning");
    assertEquals(1, submissions.size());
  }

  enum Invalidation {
    CANCEL,
    NEW_COMMAND,
    QUIT,
    RELOAD,
    EXPIRED,
    DISCONNECTED
  }

  private void invalidate(Invalidation invalidation, ReasonPrompt prompt) {
    invalidate(invalidation, prompt.cancel());
  }

  private void invalidate(Invalidation invalidation, Consumer<Player> cancel) {
    switch (invalidation) {
      case CANCEL -> cancel.accept(player);
      case NEW_COMMAND -> open();
      case QUIT -> {
        PlayerQuitEvent event = mock(PlayerQuitEvent.class);
        when(event.getPlayer()).thenReturn(player);
        menu.quit(event);
      }
      case RELOAD -> menu.closeAll();
      case EXPIRED -> clock.advance(PunishMenu.LIFETIME);
      case DISCONNECTED -> when(player.isOnline()).thenReturn(false);
    }
  }

  @ParameterizedTest
  @EnumSource(Invalidation.class)
  void cancelledReplacedQuitReloadedExpiredAndDisconnectedDraftsCannotSubmit(
      Invalidation invalidation) {
    ReasonPrompt prompt = reason(Type.KICK, null);
    invalidate(invalidation, prompt);
    prompt.confirm().accept(player, "Stale confirmation");
    assertTrue(submissions.isEmpty());
  }

  @ParameterizedTest
  @EnumSource(
      value = Invalidation.class,
      names = {"NEW_COMMAND", "QUIT", "RELOAD", "EXPIRED", "DISCONNECTED"})
  void submissionGuardExpiresWhileGatewayIsAwaitingAuthorization(Invalidation invalidation) {
    ReasonPrompt prompt = reason(Type.WARNING, null);
    prompt.confirm().accept(player, "Pending authorization");
    Submission submitted = submissions.getFirst();
    assertTrue(submitted.guard().getAsBoolean());
    invalidate(invalidation, prompt);
    assertFalse(submitted.guard().getAsBoolean());
    submitted.completion().complete(new Result(false, "STALE_SESSION", "Draft closed", null));
    assertEquals(1, submissions.size());
  }

  @Test
  void completingOldSubmissionCannotRemoveAReplacementMenu() {
    ReasonPrompt old = reason(Type.WARNING, null);
    old.confirm().accept(player, "Old submission");
    open();
    Submission previous = submissions.getFirst();
    assertFalse(previous.guard().getAsBoolean());
    previous.completion().complete(new Result(false, "STALE_SESSION", "Draft replaced", null));
    choose(new PunishMenu.Choice(Type.KICK, null));
    tick();
    tick();
    prompts.getLast().confirm().accept(player, "New submission");
    assertEquals(2, submissions.size());
    assertNotEquals(previous.actionId(), submissions.getLast().actionId());
  }

  @Test
  void guiPermissionLossWhileReasonDialogIsOpenPreventsSubmission() {
    ReasonPrompt prompt = reason(Type.WARNING, null);
    when(player.hasPermission("mineplaypunishments.gui")).thenReturn(false);
    prompt.confirm().accept(player, "Permission revoked");
    assertTrue(submissions.isEmpty());
    verify(view).closeDialog(player);
  }

  @Test
  void unavailableActionAfterMenuRenderDoesNotOpenReasonDialog() {
    open();
    when(gateway.available(player, Type.KICK, null)).thenReturn(false);
    choose(new PunishMenu.Choice(Type.KICK, null));
    tick();
    tick();
    assertTrue(prompts.isEmpty());
    assertTrue(submissions.isEmpty());
  }

  @Test
  void gatewayRejectionConsumesTheDraftWithoutControllerApplyingAnything() {
    ReasonPrompt prompt = reason(Type.WARNING, null);
    prompt.confirm().accept(player, "Permission changed during lookup");
    submissions
        .getFirst()
        .completion()
        .complete(new Result(false, "PERMISSION_DENIED", "Permission lost", null));
    prompt.confirm().accept(player, "Try old callback again");
    assertEquals(1, submissions.size());
    verify(service, never()).apply(any());
  }

  @Test
  void unavailableStorageOrCustodyCannotOpenGui() {
    when(service.healthy()).thenReturn(false);
    open();
    verify(gateway, never()).resolve(any(), anyString());
    when(service.healthy()).thenReturn(true);
    when(community.restricted(player.getUniqueId())).thenReturn(true);
    open();
    assertTrue(menus.isEmpty());
    verify(gateway, never()).resolve(any(), anyString());
  }

  @Test
  void consoleUnknownTargetsAndMissingGuiPermissionHaveNoMenuSideEffects() {
    CommandSender console = mock(CommandSender.class);
    menu.onCommand(console, null, "punish", new String[] {"Target"});
    when(player.hasPermission("mineplaypunishments.gui")).thenReturn(false);
    open();
    verify(gateway, never()).resolve(any(), anyString());
    when(player.hasPermission("mineplaypunishments.gui")).thenReturn(true);
    when(gateway.resolve(player, "Target"))
        .thenReturn(
            CompletableFuture.completedFuture(
                new Resolution(null, "UNKNOWN_TARGET", "Unknown name")));
    open();
    assertTrue(menus.isEmpty());
    assertTrue(submissions.isEmpty());
  }

  @Test
  void delayedResolutionCannotReplaceANewerSession() {
    CompletableFuture<Resolution> oldResolution = new CompletableFuture<>();
    when(gateway.resolve(player, "Old")).thenReturn(oldResolution);
    menu.onCommand(player, null, "punish", new String[] {"Old"});
    assertTrue(menus.isEmpty());
    open();
    ShownMenu replacement = shown();
    oldResolution.complete(
        new Resolution(new Target(UUID.randomUUID(), "Old"), "RESOLVED", "Old result"));
    assertEquals(1, menus.size());
    assertSame(replacement.inventory(), currentTop);
    assertEquals(target, replacement.target());
  }

  @Test
  void staleInventoryEventsCannotActOnNewSession() {
    open();
    Inventory previous = currentTop;
    open();
    InventoryClickEvent stale = click(previous, player, 10, ClickType.LEFT, false);
    verify(stale).setCancelled(true);
    assertTrue(scheduled.isEmpty());
    assertEquals(2, menus.size());
  }

  @ParameterizedTest
  @ValueSource(ints = {100, 250, 500, 1000, 2500, 5000})
  void servicePresetsRequireConfirmationAndKeepTheServerSelectedAmount(int blocks) {
    ServicePrompt prompt = serviceReason(blocks);
    assertEquals(blocks, prompt.presetBlocks());
    assertSame(player, prompt.actor());
    assertEquals(target, prompt.target());
    assertEquals(10, prompt.minimum());
    assertEquals(10000, prompt.maximum());
    assertEquals(1000, prompt.maxReason());
    assertTrue(serviceSubmissions.isEmpty());
    assertTrue(prompts.isEmpty());
    prompt
        .confirm()
        .accept(player, new PunishMenu.ServiceInput("999999999999", "Verified service reason"));
    assertEquals(1, serviceSubmissions.size());
    ServiceSubmission submitted = serviceSubmissions.getFirst();
    assertSame(player, submitted.actor());
    assertEquals(target.id(), submitted.target());
    assertNotNull(submitted.actionId());
    assertEquals(blocks, submitted.blocks(), "Client fields cannot alter a server-owned preset");
    assertEquals("Verified service reason", submitted.reason());
    assertTrue(submitted.guard().getAsBoolean());
    assertTrue(submissions.isEmpty(), "Community Service must use the dedicated gateway");
    verify(service, never()).apply(any());
  }

  @ParameterizedTest
  @ValueSource(ints = {10, 375, 10000})
  void customServiceSupportsConfiguredBoundariesAndIntermediateWholeAmounts(int blocks) {
    ServicePrompt prompt = serviceReason(null);
    assertNull(prompt.presetBlocks());
    assertTrue(serviceSubmissions.isEmpty());
    prompt
        .confirm()
        .accept(player, new PunishMenu.ServiceInput(Integer.toString(blocks), "Custom work"));
    assertEquals(1, serviceSubmissions.size());
    assertEquals(blocks, serviceSubmissions.getFirst().blocks());
    assertEquals("Custom work", serviceSubmissions.getFirst().reason());
    assertTrue(submissions.isEmpty());
  }

  @Test
  void customServiceTrimsOuterWhitespaceBeforeValidatingTheWholeNumber() {
    ServicePrompt prompt = serviceReason(null);
    prompt.confirm().accept(player, new PunishMenu.ServiceInput("  375  ", "Custom work"));
    assertEquals(375, serviceSubmissions.getFirst().blocks());
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(
      strings = {
        "",
        " ",
        "0",
        "9",
        "10001",
        "-10",
        "+100",
        "1.5",
        "1e3",
        "1 000",
        "1,000",
        "1\n00",
        "１２３",
        "١٢٣",
        "NaN",
        "2147483648",
        "9999999999",
        "10000000000"
      })
  void invalidCustomAmountsCloseTheDraftWithoutAnyGatewaySubmission(String input) {
    ServicePrompt prompt = serviceReason(null);
    prompt.confirm().accept(player, new PunishMenu.ServiceInput(input, "Invalid work amount"));
    assertTrue(serviceSubmissions.isEmpty());
    assertTrue(submissions.isEmpty());
    verify(view).closeDialog(player);
    prompt
        .confirm()
        .accept(player, new PunishMenu.ServiceInput("100", "Old callback cannot recover"));
    assertTrue(serviceSubmissions.isEmpty());
  }

  @Test
  void missingServiceResponseFailsClosed() {
    ServicePrompt prompt = serviceReason(null);
    prompt.confirm().accept(player, null);
    assertTrue(serviceSubmissions.isEmpty());
    verify(view).closeDialog(player);
  }

  @Test
  void presetCountsAreRecheckedAgainstCurrentLimitsAtConfirmation() {
    ServicePrompt prompt = serviceReason(1000);
    when(settings.maximumBlocks()).thenReturn(500);
    prompt.confirm().accept(player, new PunishMenu.ServiceInput(null, "Limits changed"));
    assertTrue(serviceSubmissions.isEmpty());
    verify(view).closeDialog(player);
  }

  @Test
  void configuredServicePresetsAreUniqueBoundedAndLimitedToSix() {
    config.set(
        "community-service.gui-block-presets",
        List.of(9, 100, 100, 250, 500, 1000, 2500, 5000, 7500, 10001));
    serviceBlocks();
    List<PunishMenu.Button> presets =
        shown().buttons().stream()
            .filter(button -> button.choice() != null && button.choice().blocks() != null)
            .toList();
    assertEquals(
        List.of(100, 250, 500, 1000, 2500, 5000),
        presets.stream().map(button -> button.choice().blocks()).toList());
    assertEquals(
        List.of(10, 11, 12, 13, 14, 15), presets.stream().map(PunishMenu.Button::slot).toList());
    assertTrue(
        shown().buttons().stream()
            .anyMatch(button -> button.choice() == PunishMenu.Choice.CUSTOM_SERVICE));
    assertEquals(
        shown().buttons().size(),
        shown().buttons().stream().map(PunishMenu.Button::slot).distinct().count());
  }

  @Test
  void customServiceRemainsAvailableWhenEveryConfiguredPresetIsOutsideLimits() {
    config.set("community-service.gui-block-presets", List.of(1, 10001));
    serviceBlocks();
    assertTrue(
        shown().buttons().stream()
            .noneMatch(button -> button.choice() != null && button.choice().blocks() != null));
    choose(PunishMenu.Choice.CUSTOM_SERVICE);
    tick();
    tick();
    assertNull(servicePrompts.getFirst().presetBlocks());
  }

  @Test
  void serviceMenuBackAndCancelNeverIssueAPunishment() {
    serviceBlocks();
    choose(PunishMenu.Choice.BACK);
    tick();
    assertTrue(
        shown().buttons().stream()
            .anyMatch(button -> new PunishMenu.Choice(Type.BAN, null).equals(button.choice())));
    choose(PunishMenu.Choice.CANCEL);
    tick();
    assertTrue(servicePrompts.isEmpty());
    assertTrue(serviceSubmissions.isEmpty());
    assertTrue(submissions.isEmpty());
  }

  @Test
  void unavailableServiceIsShownDisabledAndCannotBeSelected() {
    when(gateway.availableService(player)).thenReturn(false);
    open();
    PunishMenu.Button button =
        shown().buttons().stream()
            .filter(candidate -> candidate.label().equals("Community Service"))
            .findFirst()
            .orElseThrow();
    assertEquals(org.bukkit.Material.GRAY_DYE, button.icon());
    assertNull(button.choice());
    click(currentTop, player, button.slot(), ClickType.LEFT, false);
    assertTrue(scheduled.isEmpty());
    assertTrue(servicePrompts.isEmpty());
    assertTrue(serviceSubmissions.isEmpty());
  }

  @Test
  void serviceReadinessLossAfterTypeRenderPreventsTheBlocksMenu() {
    open();
    when(gateway.availableService(player)).thenReturn(false);
    choose(new PunishMenu.Choice(Type.COMMUNITY_SERVICE, null));
    tick();
    assertEquals(1, menus.size());
    assertTrue(servicePrompts.isEmpty());
    assertTrue(serviceSubmissions.isEmpty());
  }

  @Test
  void serviceReadinessLossAfterBlocksRenderPreventsTheReasonDialog() {
    serviceBlocks();
    when(gateway.availableService(player)).thenReturn(false);
    choose(PunishMenu.Choice.CUSTOM_SERVICE);
    tick();
    tick();
    assertTrue(servicePrompts.isEmpty());
    assertTrue(serviceSubmissions.isEmpty());
  }

  @Test
  void wrongPlayerCannotConfirmOrCancelAServiceDraftEvenWithTheOwnersUuid() {
    ServicePrompt prompt = serviceReason(null);
    Player impostor = mock(Player.class);
    UUID owner = player.getUniqueId();
    when(impostor.getUniqueId()).thenReturn(owner);
    prompt.cancel().accept(impostor);
    prompt.confirm().accept(impostor, new PunishMenu.ServiceInput("100", "Stolen callback"));
    assertTrue(serviceSubmissions.isEmpty());
    prompt.confirm().accept(player, new PunishMenu.ServiceInput("100", "Actual staff member"));
    assertEquals(1, serviceSubmissions.size());
  }

  @Test
  void duplicateServiceConfirmationCannotChangeTheAmountOrCreateAnotherSubmission() {
    ServicePrompt prompt = serviceReason(null);
    prompt.confirm().accept(player, new PunishMenu.ServiceInput("100", "Original sentence"));
    prompt.confirm().accept(player, new PunishMenu.ServiceInput("5000", "Changed payload"));
    assertEquals(1, serviceSubmissions.size());
    assertEquals(100, serviceSubmissions.getFirst().blocks());
    serviceSubmissions
        .getFirst()
        .completion()
        .complete(new Result(true, "APPLIED", "Applied", null));
    prompt.confirm().accept(player, new PunishMenu.ServiceInput("5000", "Replay after completion"));
    assertEquals(1, serviceSubmissions.size());
  }

  @ParameterizedTest
  @EnumSource(Invalidation.class)
  void invalidatedServiceDraftsCannotSubmit(Invalidation invalidation) {
    ServicePrompt prompt = serviceReason(null);
    invalidate(invalidation, prompt.cancel());
    prompt.confirm().accept(player, new PunishMenu.ServiceInput("100", "Stale service draft"));
    assertTrue(serviceSubmissions.isEmpty());
  }

  @ParameterizedTest
  @EnumSource(
      value = Invalidation.class,
      names = {"NEW_COMMAND", "QUIT", "RELOAD", "EXPIRED", "DISCONNECTED"})
  void serviceGuardExpiresWhileTheGatewayAwaitsAuthorization(Invalidation invalidation) {
    ServicePrompt prompt = serviceReason(100);
    prompt.confirm().accept(player, new PunishMenu.ServiceInput(null, "Pending authorization"));
    ServiceSubmission submitted = serviceSubmissions.getFirst();
    assertTrue(submitted.guard().getAsBoolean());
    invalidate(invalidation, prompt.cancel());
    assertFalse(submitted.guard().getAsBoolean());
    submitted.completion().complete(new Result(false, "STALE_SESSION", "Draft closed", null));
    assertEquals(1, serviceSubmissions.size());
  }

  @Test
  void oldServiceCompletionCannotRemoveAReplacementServiceDraft() {
    ServicePrompt old = serviceReason(100);
    old.confirm().accept(player, new PunishMenu.ServiceInput(null, "Old sentence"));
    ServiceSubmission submitted = serviceSubmissions.getFirst();
    ServicePrompt replacement = serviceReason(null);
    assertFalse(submitted.guard().getAsBoolean());
    submitted.completion().complete(new Result(false, "STALE_SESSION", "Draft replaced", null));
    replacement
        .confirm()
        .accept(player, new PunishMenu.ServiceInput("250", "Replacement sentence"));
    assertEquals(2, serviceSubmissions.size());
    assertNotEquals(submitted.actionId(), serviceSubmissions.getLast().actionId());
  }

  @Test
  void serviceGatewayRejectionConsumesTheDraftWithoutDirectMutation() {
    ServicePrompt prompt = serviceReason(100);
    prompt.confirm().accept(player, new PunishMenu.ServiceInput(null, "Quarry became unavailable"));
    serviceSubmissions
        .getFirst()
        .completion()
        .complete(new Result(false, "QUARRY_UNAVAILABLE", "Quarry unavailable", null));
    prompt.confirm().accept(player, new PunishMenu.ServiceInput(null, "Replay rejected draft"));
    assertEquals(1, serviceSubmissions.size());
    verify(service, never()).apply(any());
  }

  @Test
  void lostGuiPermissionPreventsServiceConfirmation() {
    ServicePrompt prompt = serviceReason(null);
    when(player.hasPermission("mineplaypunishments.gui")).thenReturn(false);
    prompt.confirm().accept(player, new PunishMenu.ServiceInput("100", "Permission revoked"));
    assertTrue(serviceSubmissions.isEmpty());
    verify(view).closeDialog(player);
  }

  private static final class MutableClock extends Clock {
    private Instant now = Instant.parse("2026-10-18T12:00:00Z");

    void advance(Duration duration) {
      now = now.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
