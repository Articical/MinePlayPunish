package cloud.mineplay.punishments.gui;

import cloud.mineplay.punishments.MineplayPunishments;
import cloud.mineplay.punishments.commands.StaffModeration;
import cloud.mineplay.punishments.commands.StaffModeration.Target;
import cloud.mineplay.punishments.punishment.Models.Result;
import cloud.mineplay.punishments.punishment.Models.Type;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;

/** Main-thread drafts. Only the explicit dialog confirmation can reach the moderation gateway. */
public final class PunishMenu implements CommandExecutor, TabCompleter, Listener {
  static final Duration LIFETIME = Duration.ofMinutes(2);
  private static final List<Integer> DEFAULT_BLOCK_PRESETS =
      List.of(100, 250, 500, 1000, 2500, 5000);
  private final MineplayPunishments plugin;
  private final StaffModeration moderation;
  private final View view;
  private final Clock clock;
  private final Map<UUID, Session> sessions = new HashMap<>();

  public PunishMenu(MineplayPunishments plugin) {
    this(plugin, new StaffModeration(plugin), new PaperMenuView(), Clock.systemUTC());
  }

  PunishMenu(MineplayPunishments plugin, StaffModeration moderation, View view, Clock clock) {
    this.plugin = plugin;
    this.moderation = moderation;
    this.view = view;
    this.clock = clock;
  }

  public void register() {
    var command = Objects.requireNonNull(plugin.getCommand("punish"));
    command.setExecutor(this);
    command.setTabCompleter(this);
    Bukkit.getPluginManager().registerEvents(this, plugin);
    Bukkit.getScheduler().runTaskTimer(plugin, this::expire, 20L, 20L);
  }

  @Override
  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    if (!(sender instanceof Player player)) {
      error(
          sender, "The punishment menu is available in-game. Console can use moderation commands.");
      return true;
    }
    cancel(player);
    if (!allowed(player)) return true;
    if (args.length != 1 || args[0].isBlank()) {
      error(player, "Use /punish <player|uuid>");
      return true;
    }
    Session session = new Session(player, clock.instant().plus(LIFETIME));
    sessions.put(player.getUniqueId(), session);
    moderation
        .resolve(player, args[0])
        .thenAccept(
            result -> {
              if (!current(session)) return;
              if (!result.resolved()) {
                sessions.remove(player.getUniqueId(), session);
                return;
              }
              session.target = result.target();
              showTypes(session);
            });
    return true;
  }

  private boolean allowed(Player player) {
    String refusal =
        !player.isOnline()
            ? "Your staff session has ended."
            : !player.hasPermission("mineplaypunishments.gui")
                ? "You do not have permission to use /punish."
                : !plugin.punishments().healthy()
                    ? "Moderation storage is unavailable."
                    : plugin.community().restricted(player.getUniqueId())
                        ? "Menus are unavailable during Community Service."
                        : null;
    if (refusal != null) {
      error(player, refusal);
      return false;
    }
    return true;
  }

  private void showTypes(Session session) {
    if (!current(session) || !allowed(session.player)) {
      cancel(session.player);
      return;
    }
    session.type = null;
    session.blocks = null;
    List<Button> buttons = new ArrayList<>();
    Type[] types = {Type.BAN, Type.MUTE, Type.KICK, Type.WARNING};
    Material[] icons = {Material.IRON_AXE, Material.BOOK, Material.LEATHER_BOOTS, Material.PAPER};
    for (int i = 0; i < types.length; i++) {
      Type type = types[i];
      boolean enabled =
          moderation.available(session.player, type, null)
              || (timed(type)
                  && moderation.available(session.player, type, DurationPreset.HOUR.duration()));
      buttons.add(
          new Button(
              10 + i * 2,
              enabled ? icons[i] : Material.GRAY_DYE,
              label(type),
              enabled
                  ? timed(type) ? "Choose a duration" : "Enter a reason and confirm"
                  : "Unavailable: permission or server policy",
              enabled ? new Choice(type, null) : null));
    }
    boolean serviceAvailable = moderation.availableService(session.player);
    buttons.add(
        new Button(
            22,
            serviceAvailable ? Material.WOODEN_PICKAXE : Material.GRAY_DYE,
            "Community Service",
            serviceAvailable
                ? "Choose a block amount"
                : "Requires service permission and a validated quarry",
            serviceAvailable ? new Choice(Type.COMMUNITY_SERVICE, null) : null));
    buttons.add(
        new Button(
            26, Material.BARRIER, "Cancel", "Close without issuing a punishment", Choice.CANCEL));
    showInventory(session, "Punish: " + targetLabel(session.target), buttons);
  }

  private void showDurations(Session session) {
    List<Button> buttons = new ArrayList<>();
    int slot = 10;
    for (DurationPreset preset : DurationPreset.values()) {
      boolean enabled = moderation.available(session.player, session.type, preset.duration());
      buttons.add(
          new Button(
              slot++,
              enabled
                  ? preset == DurationPreset.PERMANENT ? Material.REDSTONE_BLOCK : Material.CLOCK
                  : Material.GRAY_DYE,
              preset.label(),
              enabled ? "Enter a reason and confirm" : "Unavailable: permission or server policy",
              enabled ? new Choice(session.type, preset) : null));
    }
    buttons.add(new Button(22, Material.ARROW, "Back", "Choose another punishment", Choice.BACK));
    buttons.add(
        new Button(
            26, Material.BARRIER, "Cancel", "Close without issuing a punishment", Choice.CANCEL));
    showInventory(session, label(session.type) + ": choose duration", buttons);
  }

  private void showBlocks(Session session) {
    if (!moderation.availableService(session.player)) {
      error(
          session.player,
          "Community Service is unavailable. Check permission and quarry validation.");
      cancel(session.player);
      return;
    }
    int minimum = plugin.settings().minimumBlocks();
    int maximum = plugin.settings().maximumBlocks();
    List<Integer> configured =
        plugin.getConfig().contains("community-service.gui-block-presets")
            ? plugin.getConfig().getIntegerList("community-service.gui-block-presets")
            : DEFAULT_BLOCK_PRESETS;
    List<Button> buttons = new ArrayList<>();
    int slot = 10;
    for (int blocks :
        configured.stream()
            .filter(n -> n >= minimum && n <= maximum)
            .distinct()
            .limit(6)
            .toList()) {
      buttons.add(
          new Button(
              slot++,
              Material.STONE,
              blocks + " blocks",
              "Enter a reason and confirm",
              new Choice(Type.COMMUNITY_SERVICE, null, null, blocks)));
    }
    buttons.add(
        new Button(
            16,
            Material.WRITABLE_BOOK,
            "Custom amount",
            "Enter a whole number: " + minimum + " to " + maximum,
            Choice.CUSTOM_SERVICE));
    buttons.add(new Button(22, Material.ARROW, "Back", "Choose another punishment", Choice.BACK));
    buttons.add(
        new Button(
            26, Material.BARRIER, "Cancel", "Close without issuing a punishment", Choice.CANCEL));
    showInventory(session, "Community Service: blocks", buttons);
  }

  private void showInventory(Session session, String title, List<Button> buttons) {
    // Detach the old inventory before opening another; its close event is then harmless.
    session.inventory = null;
    session.stage = Stage.MENU;
    session.pendingClick = false;
    session.buttons = List.copyOf(buttons);
    Inventory opened =
        view.open(session.player, session.id, session.target, title, session.buttons);
    session.inventory = opened;
    if (session.player.getOpenInventory().getTopInventory() != opened) cancel(session.player);
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void click(InventoryClickEvent event) {
    Inventory top = event.getView().getTopInventory();
    if (!(top.getHolder() instanceof PaperMenuView.Holder)) return;
    boolean alreadyCancelled = event.isCancelled();
    event.setCancelled(true);
    if (alreadyCancelled
        || event.getClick() != ClickType.LEFT
        || !(event.getWhoClicked() instanceof Player player)) return;
    Session session = sessions.get(player.getUniqueId());
    if (session == null
        || session.player != player
        || session.inventory != top
        || !current(session)
        || session.stage != Stage.MENU
        || session.pendingClick) return;
    Choice choice =
        session.buttons.stream()
            .filter(b -> b.slot() == event.getRawSlot())
            .map(Button::choice)
            .filter(Objects::nonNull)
            .findFirst()
            .orElse(null);
    if (choice == null) return;
    session.pendingClick = true;
    // Inventory API requires screen transitions outside InventoryClickEvent processing.
    Bukkit.getScheduler()
        .runTask(
            plugin,
            () -> {
              if (!current(session)
                  || session.inventory != player.getOpenInventory().getTopInventory()) return;
              session.pendingClick = false;
              if (choice.navigation() == Navigation.CANCEL) {
                cancel(player);
                return;
              }
              if (!allowed(player)) {
                cancel(player);
                return;
              }
              if (choice.navigation() == Navigation.BACK) {
                showTypes(session);
                return;
              }
              session.type = choice.type();
              if (choice.type() == Type.COMMUNITY_SERVICE
                  && choice.blocks() == null
                  && choice.navigation() != Navigation.CUSTOM_SERVICE) {
                showBlocks(session);
                return;
              }
              if (timed(choice.type()) && choice.preset() == null) {
                showDurations(session);
              } else {
                session.preset = choice.preset();
                session.blocks = choice.blocks();
                boolean available =
                    session.type == Type.COMMUNITY_SERVICE
                        ? moderation.availableService(player)
                        : moderation.available(player, session.type, duration(session));
                if (!available) {
                  error(player, "That action is no longer available.");
                  cancel(player);
                  return;
                }
                session.inventory = null;
                session.stage = Stage.REASON;
                player.closeInventory();
                Bukkit.getScheduler()
                    .runTask(
                        plugin,
                        () -> {
                          if (current(session) && session.stage == Stage.REASON)
                            showReason(session);
                        });
              }
            });
  }

  private void showReason(Session session) {
    if (session.type == Type.COMMUNITY_SERVICE) {
      view.serviceReason(
          session.player,
          session.target,
          session.blocks,
          plugin.settings().minimumBlocks(),
          plugin.settings().maximumBlocks(),
          plugin.settings().maximumReasonLength(),
          (actor, input) -> plugin.onMain(() -> confirmService(session, actor, input)),
          actor ->
              plugin.onMain(
                  () -> {
                    if (actor == session.player && current(session)) cancel(actor);
                  }));
      return;
    }
    view.reason(
        session.player,
        session.target,
        session.type,
        session.preset,
        plugin.settings().maximumReasonLength(),
        (actor, reason) -> plugin.onMain(() -> confirm(session, actor, reason)),
        actor ->
            plugin.onMain(
                () -> {
                  if (actor == session.player && current(session)) cancel(actor);
                }));
  }

  private void confirm(Session session, Player actor, String reason) {
    if (actor != session.player || !current(session) || session.stage != Stage.REASON) return;
    if (!allowed(actor)) {
      cancel(actor);
      return;
    }
    session.stage = Stage.SUBMITTING; // Consume before the asynchronous hierarchy lookup.
    completeSubmission(
        session,
        moderation.submit(
            actor,
            session.target.id(),
            session.actionId,
            session.type,
            duration(session),
            reason,
            () -> current(session) && session.stage == Stage.SUBMITTING));
  }

  private void confirmService(Session session, Player actor, ServiceInput input) {
    if (actor != session.player || !current(session) || session.stage != Stage.REASON) return;
    if (!allowed(actor)) {
      cancel(actor);
      return;
    }
    int blocks;
    try {
      if (input == null) throw new IllegalArgumentException();
      // A preset is fixed by the server; ignore any amount supplied by the client for that form.
      if (session.blocks != null) blocks = session.blocks;
      else {
        String raw = input.blocks() == null ? "" : input.blocks().strip();
        if (!raw.matches("[0-9]{1,10}")) throw new IllegalArgumentException();
        blocks = Integer.parseInt(raw);
      }
      if (blocks < plugin.settings().minimumBlocks() || blocks > plugin.settings().maximumBlocks())
        throw new IllegalArgumentException();
    } catch (IllegalArgumentException invalid) {
      error(
          actor,
          "Blocks must be a whole number between "
              + plugin.settings().minimumBlocks()
              + " and "
              + plugin.settings().maximumBlocks()
              + ". Reopen /punish to try again.");
      cancel(actor);
      return;
    }
    session.stage = Stage.SUBMITTING;
    completeSubmission(
        session,
        moderation.submitService(
            actor,
            session.target.id(),
            session.actionId,
            blocks,
            input.reason(),
            () -> current(session) && session.stage == Stage.SUBMITTING));
  }

  private void completeSubmission(Session session, CompletableFuture<Result> completion) {
    Player actor = session.player;
    completion.whenComplete(
        (result, failure) ->
            plugin.onMain(
                () -> {
                  sessions.remove(actor.getUniqueId(), session);
                  if (failure != null) {
                    plugin.logFailure("gui_submit", failure);
                    error(
                        actor,
                        "The action could not be confirmed. Check history before trying"
                            + " again.");
                  }
                }));
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void drag(InventoryDragEvent event) {
    if (event.getView().getTopInventory().getHolder() instanceof PaperMenuView.Holder)
      event.setCancelled(true);
  }

  @EventHandler
  public void close(InventoryCloseEvent event) {
    Session session = sessions.get(event.getPlayer().getUniqueId());
    if (session != null && session.inventory == event.getInventory())
      sessions.remove(event.getPlayer().getUniqueId(), session);
  }

  @EventHandler
  public void quit(PlayerQuitEvent event) {
    sessions.remove(event.getPlayer().getUniqueId());
  }

  private boolean current(Session session) {
    return sessions.get(session.player.getUniqueId()) == session
        && session.player.isOnline()
        && clock.instant().isBefore(session.expires);
  }

  private void expire() {
    for (Session session : List.copyOf(sessions.values())) {
      if (!current(session)) {
        cancel(session.player);
        if (session.player.isOnline())
          error(session.player, "Punishment menu expired. Use /punish to start again.");
      }
    }
  }

  private void cancel(Player player) {
    Session old = sessions.remove(player.getUniqueId());
    if (old == null) return;
    if (old.inventory != null && player.getOpenInventory().getTopInventory() == old.inventory)
      player.closeInventory();
    if (old.stage == Stage.REASON) view.closeDialog(player);
  }

  /** Invalidates pending callbacks, including drafts waiting on an offline hierarchy lookup. */
  public void closeAll() {
    for (Session session : List.copyOf(sessions.values())) cancel(session.player);
  }

  @Override
  public List<String> onTabComplete(
      CommandSender sender, Command command, String alias, String[] args) {
    if (!(sender instanceof Player player)
        || !player.hasPermission("mineplaypunishments.gui")
        || args.length != 1) return List.of();
    String prefix = args[0].toLowerCase(Locale.ROOT);
    return Bukkit.getOnlinePlayers().stream()
        .filter(player::canSee)
        .map(Player::getName)
        .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
        .sorted()
        .toList();
  }

  private void error(CommandSender sender, String detail) {
    sender.sendMessage(plugin.messages().text("error", "message", detail));
  }

  private static boolean timed(Type type) {
    return type == Type.BAN || type == Type.MUTE;
  }

  private static Duration duration(Session session) {
    return session.preset == null ? null : session.preset.duration();
  }

  static String targetLabel(Target target) {
    return target.name() == null ? target.id().toString() : target.name();
  }

  static String label(Type type) {
    return switch (type) {
      case BAN -> "Ban";
      case MUTE -> "Mute";
      case KICK -> "Kick";
      case WARNING -> "Warn";
      case COMMUNITY_SERVICE -> "Community Service";
    };
  }

  record Button(int slot, Material icon, String label, String detail, Choice choice) {}

  record Choice(Type type, DurationPreset preset, Navigation navigation, Integer blocks) {
    Choice(Type type, DurationPreset preset) {
      this(type, preset, null, null);
    }

    static final Choice CANCEL = new Choice(null, null, Navigation.CANCEL, null);
    static final Choice BACK = new Choice(null, null, Navigation.BACK, null);
    static final Choice CUSTOM_SERVICE =
        new Choice(Type.COMMUNITY_SERVICE, null, Navigation.CUSTOM_SERVICE, null);
  }

  record ServiceInput(String blocks, String reason) {}

  enum Navigation {
    BACK,
    CANCEL,
    CUSTOM_SERVICE
  }

  interface View {
    Inventory open(
        Player player, UUID sessionId, Target target, String title, List<Button> buttons);

    void reason(
        Player player,
        Target target,
        Type type,
        DurationPreset preset,
        int maximumReasonLength,
        BiConsumer<Player, String> confirm,
        Consumer<Player> cancel);

    void serviceReason(
        Player player,
        Target target,
        Integer presetBlocks,
        int minimumBlocks,
        int maximumBlocks,
        int maximumReasonLength,
        BiConsumer<Player, ServiceInput> confirm,
        Consumer<Player> cancel);

    void closeDialog(Player player);
  }

  private enum Stage {
    RESOLVING,
    MENU,
    REASON,
    SUBMITTING
  }

  private static final class Session {
    final Player player;
    final UUID id = UUID.randomUUID();
    final UUID actionId = UUID.randomUUID();
    final Instant expires;
    Target target;
    Type type;
    DurationPreset preset;
    Integer blocks;
    Inventory inventory;
    List<Button> buttons = List.of();
    boolean pendingClick;
    Stage stage = Stage.RESOLVING;

    Session(Player player, Instant expires) {
      this.player = player;
      this.expires = expires;
    }
  }
}
