package cloud.mineplay.punishments.communityservice;

import static cloud.mineplay.punishments.communityservice.ServiceRepository.*;

import cloud.mineplay.punishments.config.Messages;
import cloud.mineplay.punishments.punishment.Models.Sentence;
import cloud.mineplay.punishments.punishment.PunishmentService;
import cloud.mineplay.punishments.storage.Database;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Custody state is durable before equipment mutation; Bukkit access remains on the server thread.
 */
public final class CommunityServiceManager implements Listener {
  private final JavaPlugin plugin;
  private final PunishmentService service;
  private final ServiceRepository repository;
  private final NamespacedKey toolKey;
  private final NamespacedKey receiptKey;
  private final QuarryChunks quarryChunks;
  private final Set<UUID> processing = new HashSet<>(),
      prepared = new HashSet<>(),
      mutating = new HashSet<>();
  private final Set<Position> reserved = new HashSet<>(), regenerating = new HashSet<>();
  private final Map<Position, Regeneration> regeneration = new LinkedHashMap<>();
  private final ArrayDeque<Position> regenerationOrder = new ArrayDeque<>();
  private final Map<BlockBreakEvent, Candidate> candidates = new IdentityHashMap<>();
  private final Map<UUID, Location> allowedTeleports = new HashMap<>();
  private final Map<UUID, BossBar> bars = new HashMap<>();
  private QuarryConfiguration quarry;
  private List<PrisonProtection> protection = List.of();
  private Messages messages;
  private BukkitTask tickTask;
  private volatile boolean ready;
  private boolean running;
  private int ticks;

  private record Candidate(
      Player player, Sentence sentence, Position position, QuarryConfiguration configuration) {}

  public CommunityServiceManager(JavaPlugin plugin, Database database, PunishmentService service) {
    this.plugin = plugin;
    this.service = service;
    this.repository = new ServiceRepository(database, service);
    this.toolKey = new NamespacedKey(plugin, "prison_tool");
    this.receiptKey = new NamespacedKey(plugin, "restoration_receipt");
    this.quarryChunks = new QuarryChunks(plugin);
  }

  public void start() {
    repository.initialize().join();
    repository.regenerations().join().forEach(this::queueRegeneration);
    try {
      reload();
    } catch (IllegalArgumentException e) {
      plugin.getLogger().warning("Community Service unavailable: " + e.getMessage());
    }
    running = true;
    Bukkit.getPluginManager().registerEvents(this, plugin);
    tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1, 1);
    Bukkit.getOnlinePlayers().forEach(this::reconcile);
  }

  public void stop() {
    running = false;
    ready = false;
    if (tickTask != null) tickTask.cancel();
    try {
      quarryChunks.close();
    } catch (RuntimeException failure) {
      // Remaining shutdown and the startup-failure login guard must still run.
      plugin
          .getLogger()
          .log(
              java.util.logging.Level.SEVERE,
              "event=quarry_chunk_cleanup_failed Continuing Community Service shutdown",
              failure);
    }
    Bukkit.getOnlinePlayers()
        .forEach(
            p -> {
              hideBar(p);
              if (restricted(p.getUniqueId())) p.saveData();
            });
    HandlerList.unregisterAll(this);
  }

  public void reload() {
    ready = false;
    quarry = null;
    quarryChunks.close();
    messages = new Messages(plugin);
    protection = PrisonProtection.read(plugin.getConfig());
    try {
      quarry = QuarryConfiguration.load(plugin.getConfig(), quarryChunks::acquire);
    } catch (RuntimeException failure) {
      try {
        quarryChunks.close();
      } catch (RuntimeException cleanup) {
        failure.addSuppressed(cleanup);
      }
      if (failure instanceof IllegalArgumentException invalid) throw invalid;
      throw new IllegalArgumentException("Could not prepare quarry chunks", failure);
    }
    ready = quarry != null;
  }

  /** Safe for async remote-action semantic validation: no live Bukkit access. */
  public boolean ready() {
    return ready;
  }

  public boolean restricted(UUID id) {
    return processing.contains(id)
        || service.sentence(id).filter(s -> !s.state().equals("COMPLETED")).isPresent();
  }

  public String progress(UUID id) {
    return service
        .sentence(id)
        .map(
            s ->
                String.format(
                    Locale.ROOT,
                    "COMMUNITY SERVICE - %d / %d BLOCKS - %.1f%% (%s)",
                    s.completed(),
                    s.required(),
                    Math.min(100, 100.0 * s.completed() / s.required()),
                    s.state()))
        .orElse("No outstanding Community Service sentence.");
  }

  public void reconcile(Player player) {
    if (!running
        || !player.isOnline()
        || processing.contains(player.getUniqueId())
        || !service.healthy()) return;
    Optional<Sentence> current = service.sentence(player.getUniqueId());
    if (current.isEmpty()) {
      prepared.remove(player.getUniqueId());
      hideBar(player);
      return;
    }
    Sentence sentence = current.get();
    switch (sentence.state()) {
      case "ASSIGNED", "SNAPSHOTTED", "ACTIVE" -> {
        if (prepared.contains(player.getUniqueId()) && sentence.state().equals("ACTIVE")) return;
        if (!ready
            || quarry == null
            || !quarry.tools().contains(Material.valueOf(sentence.tool()))) {
          player.sendActionBar(
              message(
                  "service-awaiting-quarry",
                  Component.text(
                      "Community Service is awaiting a safe quarry. Contact staff.",
                      NamedTextColor.YELLOW)));
          return;
        }
        if (!prepared.contains(player.getUniqueId())
            && prepared.size() + processing.size() >= quarry.maximumPrisoners()) return;
        enter(player, sentence);
      }
      case "WORK_COMPLETE", "RESTORING" -> restore(player, sentence);
      case "RECOVERY" ->
          player.sendActionBar(
              message(
                  "service-recovery",
                  Component.text(
                      "Inventory recovery required. Contact staff; your saved inventory is"
                          + " retained.",
                      NamedTextColor.RED)));
      default -> {}
    }
  }

  private void enter(Player player, Sentence sentence) {
    // Cursor, crafting and workstation inventories are not durable in vanilla's player file.
    // Leave these untouched and defer admission until the player has closed the screen safely.
    if (sentence.state().equals("ASSIGNED") && !stableInventory(player)) {
      player.sendActionBar(
          message(
              "service-inventory-wait",
              Component.text(
                  "Close your inventory before admission. If it holds items, ask staff to release"
                      + " and reissue safely.",
                  NamedTextColor.YELLOW)));
      return;
    }
    UUID id = player.getUniqueId();
    processing.add(id);
    // Capture all immutable data now; no player access takes place on the database executor.
    CompletableFuture<Optional<Snapshot>> snapshotFuture;
    try {
      if (sentence.state().equals("ASSIGNED")) {
        String captured = PlayerState.capture(player).json();
        snapshotFuture =
            repository
                .capture(sentence, captured)
                .thenCompose(saved -> repository.snapshot(sentence.id()));
      } else snapshotFuture = repository.snapshot(sentence.id());
    } catch (Exception error) {
      fail(player, sentence, "Could not capture original inventory", error);
      return;
    }
    snapshotFuture.whenComplete(
        (snapshot, error) ->
            main(
                () -> {
                  if (error != null) {
                    fail(player, sentence, "Could not commit inventory snapshot", error);
                    return;
                  }
                  if (!sameSession(player)) {
                    processing.remove(id);
                    return;
                  }
                  try {
                    Snapshot saved =
                        snapshot.orElseThrow(
                            () -> new IllegalStateException("Durable snapshot missing"));
                    saved.verify();
                    PlayerState.parse(saved.json());
                    Sentence latest = service.sentence(id).orElse(sentence);
                    if (latest.state().equals("WORK_COMPLETE")
                        || latest.state().equals("RESTORING")) {
                      processing.remove(id);
                      reconcile(player);
                      return;
                    }
                    if (!ready || quarry == null || !QuarryConfiguration.safe(quarry.arrival()))
                      throw new IllegalStateException("Quarry no longer safe");
                    mutating.add(id);
                    try {
                      player.closeInventory();
                      player.getInventory().clear();
                      player.getInventory().setArmorContents(new ItemStack[4]);
                      player.getInventory().setItemInOffHand(null);
                      player.setItemOnCursor(null);
                      player.setGameMode(GameMode.SURVIVAL);
                      player.setFlying(false);
                      player.setAllowFlight(false);
                      player
                          .getActivePotionEffects()
                          .forEach(e -> player.removePotionEffect(e.getType()));
                      player.setLevel(0);
                      player.setExp(0);
                      player.setTotalExperience(0);
                      player.setFoodLevel(20);
                      player.setSaturation(20);
                      player.setFireTicks(0);
                      player.setFallDistance(0);
                      if (player.isInsideVehicle() && !player.leaveVehicle())
                        throw new IllegalStateException("Could not dismount prisoner safely");
                      player.eject();
                      player.getInventory().setItem(0, tool(sentence));
                      player.getInventory().setHeldItemSlot(0);
                      if (!teleport(player, quarry.arrival()))
                        throw new IllegalStateException("Prison arrival teleport rejected");
                      player.saveData();
                    } finally {
                      mutating.remove(id);
                    }
                    repository
                        .activate(sentence)
                        .thenCompose(changed -> service.refresh())
                        .whenComplete(
                            (unused, failure) ->
                                main(
                                    () -> {
                                      if (failure != null) {
                                        fail(
                                            player,
                                            sentence,
                                            "Could not confirm prison admission",
                                            failure);
                                        return;
                                      }
                                      processing.remove(id);
                                      if (sameSession(player)) {
                                        prepared.add(id);
                                        reconcile(player);
                                      }
                                    }));
                  } catch (Exception failure) {
                    fail(player, sentence, "Prison admission requires recovery", failure);
                  }
                }));
  }

  private ItemStack tool(Sentence sentence) {
    ItemStack item = new ItemStack(Material.valueOf(sentence.tool()));
    item.editMeta(
        meta -> {
          meta.displayName(Component.text("Community Service Pickaxe", NamedTextColor.GOLD));
          meta.setUnbreakable(true);
          meta.getPersistentDataContainer()
              .set(toolKey, PersistentDataType.STRING, sentence.id().toString());
        });
    return item;
  }

  private boolean authorisedTool(Player p, Sentence s) {
    ItemStack item = p.getInventory().getItemInMainHand();
    return item.getType().name().equals(s.tool())
        && item.hasItemMeta()
        && s.id()
            .toString()
            .equals(
                item.getItemMeta()
                    .getPersistentDataContainer()
                    .get(toolKey, PersistentDataType.STRING));
  }

  private void restore(Player player, Sentence sentence) {
    UUID id = player.getUniqueId();
    processing.add(id);
    repository
        .snapshot(sentence.id())
        .whenComplete(
            (found, error) ->
                main(
                    () -> {
                      if (error != null) {
                        fail(player, sentence, "Could not read recovery snapshot", error);
                        return;
                      }
                      if (!sameSession(player)) {
                        processing.remove(id);
                        return;
                      }
                      if (found.isEmpty()) {
                        // A release before first admission never altered equipment and needs no
                        // restoration.
                        repository
                            .complete(sentence, false)
                            .thenCompose(v -> service.refresh())
                            .whenComplete((v, e) -> main(() -> finish(player, sentence, e)));
                        return;
                      }
                      Snapshot snapshot = found.get();
                      try {
                        snapshot.verify();
                        PlayerState.parse(snapshot.json());
                      } catch (Exception e) {
                        fail(player, sentence, "Snapshot failed integrity validation", e);
                        return;
                      }
                      Location destination = safeRelease();
                      if (destination == null) {
                        processing.remove(id);
                        player.sendActionBar(
                            Component.text(
                                "Release is waiting for a safe destination. Contact staff.",
                                NamedTextColor.YELLOW));
                        return;
                      }
                      repository
                          .beginRestore(sentence)
                          .whenComplete(
                              (began, failure) ->
                                  main(
                                      () -> {
                                        if (failure != null) {
                                          fail(
                                              player,
                                              sentence,
                                              "Could not persist restoration intent",
                                              failure);
                                          return;
                                        }
                                        if (!began || !sameSession(player)) {
                                          processing.remove(id);
                                          return;
                                        }
                                        try {
                                          PlayerState state = PlayerState.parse(snapshot.json());
                                          mutating.add(id);
                                          try {
                                            state.apply(player);
                                            if (!teleport(player, destination)
                                                || !state.matches(player)
                                                || !at(player.getLocation(), destination))
                                              throw new IllegalStateException(
                                                  "Restored player state verification failed");
                                            // Persist Minecraft's player file before clearing
                                            // SQLite's custody state. A retry overwrites slots,
                                            // never adds items.
                                            player
                                                .getPersistentDataContainer()
                                                .set(
                                                    receiptKey,
                                                    PersistentDataType.STRING,
                                                    UUID.randomUUID().toString());
                                            player.saveData();
                                          } finally {
                                            mutating.remove(id);
                                          }
                                          String receipt =
                                              player
                                                  .getPersistentDataContainer()
                                                  .get(receiptKey, PersistentDataType.STRING);
                                          Path playerFile =
                                              PlayerDataReceipt.playerFile(Bukkit.getServer(), id);
                                          String key = receiptKey.toString();
                                          CompletableFuture.runAsync(
                                                  () -> {
                                                    try {
                                                      PlayerDataReceipt.verify(
                                                          playerFile, key, receipt);
                                                    } catch (IOException e) {
                                                      throw new CompletionException(e);
                                                    }
                                                  })
                                              .thenCompose(v -> repository.complete(sentence, true))
                                              .thenCompose(v -> service.refresh())
                                              .whenComplete(
                                                  (v, e) ->
                                                      main(() -> finish(player, sentence, e)));
                                        } catch (Exception e) {
                                          fail(
                                              player,
                                              sentence,
                                              "Inventory restoration requires staff recovery",
                                              e);
                                        }
                                      }));
                    }));
  }

  private void finish(Player player, Sentence sentence, Throwable error) {
    if (error != null) {
      fail(player, sentence, "Could not confirm inventory restoration", error);
      return;
    }
    processing.remove(player.getUniqueId());
    prepared.remove(player.getUniqueId());
    if (sameSession(player)) {
      hideBar(player);
      player.sendActionBar(Component.empty());
      player.sendMessage(
          message(
              "service-released",
              Component.text(
                  "Community Service complete. Your saved state has been restored.",
                  NamedTextColor.GREEN)));
    }
  }

  private void fail(Player player, Sentence sentence, String message, Throwable error) {
    Throwable cause = error;
    while (cause instanceof CompletionException && cause.getCause() != null)
      cause = cause.getCause();
    plugin
        .getLogger()
        .severe(
            message
                + " for "
                + player.getUniqueId()
                + " ("
                + cause.getClass().getSimpleName()
                + ": "
                + cause.getMessage()
                + "). Snapshot retained. After resolving the error, use /mp admin recover "
                + player.getName()
                + ".");
    repository
        .recovery(sentence)
        .thenCompose(v -> service.refresh())
        .whenComplete(
            (v, e) ->
                main(
                    () -> {
                      // The operation has ended even when its recovery write fails. The
                      // outstanding sentence remains restricted, but staff must be able to retry.
                      processing.remove(player.getUniqueId());
                      if (sameSession(player))
                        player.sendMessage(
                            Component.text(
                                message
                                    + ". Your saved snapshot is retained; staff can retry with /mp"
                                    + " admin recover "
                                    + player.getName()
                                    + ".",
                                NamedTextColor.RED));
                    }));
  }

  public void recover(Player player, CommandSender staff) {
    if (!staff.hasPermission("mineplaypunishments.admin.recover")) {
      staff.sendMessage(Component.text("You do not have permission.", NamedTextColor.RED));
      return;
    }
    Optional<Sentence> found = service.sentence(player.getUniqueId());
    if (found.isEmpty()) {
      staff.sendMessage(Component.text("No outstanding recovery or sentence."));
      return;
    }
    if (processing.contains(player.getUniqueId())) {
      staff.sendMessage(
          Component.text("A durable state transition is in progress. Try again shortly."));
      return;
    }
    if (safeRelease() == null) {
      staff.sendMessage(
          Component.text(
              "Configure a safe release or fallback location first.", NamedTextColor.RED));
      return;
    }
    repository
        .emergencyRelease(found.get(), staff.getName())
        .thenCompose(v -> service.refresh())
        .whenComplete(
            (v, error) ->
                main(
                    () -> {
                      if (error != null) {
                        staff.sendMessage(
                            Component.text(
                                "Recovery request could not be committed.", NamedTextColor.RED));
                        return;
                      }
                      staff.sendMessage(
                          Component.text(
                              "Recovery release recorded. Restoration will be verified before"
                                  + " custody ends."));
                      reconcile(player);
                    }));
  }

  private Location safeRelease() {
    if (quarry != null && quarry.safeRelease() != null) return quarry.safeRelease();
    for (String path :
        List.of("community-service.quarry.release", "community-service.quarry.fallback")) {
      ConfigurationSection s = plugin.getConfig().getConfigurationSection(path);
      if (s == null) continue;
      World world =
          Bukkit.getWorld(
              s.getString(
                  "world",
                  plugin.getConfig().getString("community-service.quarry.world", "world")));
      if (world == null) continue;
      Location location =
          new Location(
              world,
              s.getDouble("x"),
              s.getDouble("y"),
              s.getDouble("z"),
              (float) s.getDouble("yaw"),
              (float) s.getDouble("pitch"));
      if (!protectedBlock(location) && QuarryConfiguration.safe(location)) return location;
    }
    return null;
  }

  private boolean teleport(Player p, Location destination) {
    allowedTeleports.put(p.getUniqueId(), destination.clone());
    try {
      return p.teleport(destination, PlayerTeleportEvent.TeleportCause.PLUGIN)
          && at(p.getLocation(), destination);
    } finally {
      allowedTeleports.remove(p.getUniqueId());
    }
  }

  private static boolean at(Location a, Location b) {
    return a.getWorld().getUID().equals(b.getWorld().getUID()) && a.distanceSquared(b) < 0.001;
  }

  private boolean sameSession(Player p) {
    return p.isOnline() && Bukkit.getPlayer(p.getUniqueId()) == p;
  }

  private boolean stableInventory(Player p) {
    if (!p.getItemOnCursor().getType().isAir()
        || p.getOpenInventory().getType() != InventoryType.CRAFTING) return false;
    return !(p.getOpenInventory().getTopInventory() instanceof CraftingInventory crafting)
        || Arrays.stream(crafting.getMatrix())
            .allMatch(item -> item == null || item.getType().isAir());
  }

  private void main(Runnable task) {
    if (running && plugin.isEnabled())
      Bukkit.getScheduler()
          .runTask(
              plugin,
              () -> {
                if (running) task.run();
              });
  }

  private void tick() {
    regenerate();
    if (++ticks % 20 != 0) return;
    ready =
        quarry != null
            && Bukkit.getWorld(quarry.world().getUID()) != null
            && quarryChunks.loaded()
            && QuarryConfiguration.safe(quarry.arrival())
            && quarry.safeRelease() != null;
    for (Player player : Bukkit.getOnlinePlayers()) {
      reconcile(player);
      service
          .sentence(player.getUniqueId())
          .ifPresent(
              s -> {
                if (s.state().equals("RECOVERY")) {
                  // reconcile already shows the recovery explanation; do not immediately
                  // overwrite it with a misleading 100% mining-progress display.
                  hideBar(player);
                  return;
                }
                Component text =
                    message(
                        "service-progress",
                        Component.text(progress(player.getUniqueId()), NamedTextColor.GOLD),
                        "completed",
                        Integer.toString(s.completed()),
                        "required",
                        Integer.toString(s.required()),
                        "percent",
                        String.format(
                            Locale.ROOT,
                            "%.1f",
                            Math.min(100, 100.0 * s.completed() / s.required())),
                        "state",
                        s.state());
                if (quarry == null || quarry.actionBar()) player.sendActionBar(text);
                if (quarry != null && quarry.bossBar()) {
                  BossBar bar =
                      bars.computeIfAbsent(
                          player.getUniqueId(),
                          id -> {
                            BossBar created =
                                BossBar.bossBar(
                                    text, 0, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
                            player.showBossBar(created);
                            return created;
                          });
                  bar.name(text)
                      .progress(
                          (float) Math.max(0, Math.min(1, (double) s.completed() / s.required())));
                }
                if (prepared.contains(player.getUniqueId())
                    && s.state().equals("ACTIVE")
                    && quarry != null
                    && !quarry.containsPrisoner(player.getLocation())) {
                  if (QuarryConfiguration.safe(quarry.arrival()))
                    teleport(player, quarry.arrival());
                }
              });
    }
  }

  private void hideBar(Player player) {
    BossBar bar = bars.remove(player.getUniqueId());
    if (bar != null) player.hideBossBar(bar);
  }

  private void regenerate() {
    if (quarry == null) return;
    int limit = quarry.regenerationLimit(),
        processed = 0,
        inspected = 0,
        available = regenerationOrder.size();
    long now = System.currentTimeMillis();
    while (processed < limit
        && inspected++ < Math.min(available, limit * 4)
        && !regenerationOrder.isEmpty()) {
      Position position = regenerationOrder.removeFirst();
      Regeneration entry = regeneration.get(position);
      if (entry == null) continue;
      regenerationOrder.addLast(position);
      if (entry.due() > now || regenerating.contains(position)) continue;
      World world = Bukkit.getWorld(position.world());
      if (world == null || !world.isChunkLoaded(position.x() >> 4, position.z() >> 4)) continue;
      if (!world.getUID().equals(quarry.world().getUID())
          || !quarry.bounds().contains(position.x(), position.y(), position.z())) continue;
      processed++;
      Block block = world.getBlockAt(position.x(), position.y(), position.z());
      // Only the exact expected empty state can be replaced. A changed block is left untouched.
      if (block.getType().isAir()) block.setType(Material.valueOf(entry.material()), false);
      regenerating.add(position);
      repository
          .finishRegeneration(position)
          .whenComplete(
              (v, e) ->
                  main(
                      () -> {
                        regenerating.remove(position);
                        if (e == null) {
                          regeneration.remove(position);
                          reserved.remove(position);
                        }
                      }));
    }
  }

  private void queueRegeneration(Regeneration row) {
    if (regeneration.putIfAbsent(row.position(), row) == null)
      regenerationOrder.addLast(row.position());
  }

  private static Position position(Block block) {
    return new Position(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
  }

  private boolean protectedBlock(Location location) {
    return (quarry != null && quarry.containsProtected(location))
        || protection.stream().anyMatch(region -> region.contains(location));
  }

  private boolean protectedFromBuilder(Player player, Location location) {
    return protectedBlock(location) && !player.hasPermission("mineplaypunishments.prison.build");
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void breakBlock(BlockBreakEvent event) {
    Player p = event.getPlayer();
    Block block = event.getBlock();
    boolean inside = quarry != null && quarry.contains(block.getLocation());
    if (!restricted(p.getUniqueId())) {
      if (protectedFromBuilder(p, block.getLocation())) event.setCancelled(true);
      return;
    }
    Optional<Sentence> sentence = service.sentence(p.getUniqueId());
    Position pos = position(block);
    boolean valid =
        sentence.isPresent()
            && quarry != null
            && MiningRules.eligible(
                sentence.get().state().equals("ACTIVE")
                    && !processing.contains(p.getUniqueId())
                    && service.healthy(),
                p.getGameMode() == GameMode.SURVIVAL,
                inside,
                quarry.blocks().contains(block.getType()),
                authorisedTool(p, sentence.get()),
                quarry.tools().contains(p.getInventory().getItemInMainHand().getType()),
                reserved.contains(pos) || regeneration.containsKey(pos));
    if (!valid) {
      event.setCancelled(true);
      return;
    }
    event.setDropItems(false);
    event.setExpToDrop(0);
    reserved.add(pos);
    candidates.put(event, new Candidate(p, sentence.get(), pos, quarry));
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void finaliseBreak(BlockBreakEvent event) {
    Candidate candidate = candidates.remove(event);
    if (candidate == null) return;
    // Defer until every handler, including later MONITOR handlers, has returned.
    Bukkit.getScheduler()
        .runTask(
            plugin,
            () -> {
              Position pos = candidate.position();
              Player p = candidate.player();
              Optional<Sentence> latest = service.sentence(p.getUniqueId());
              boolean active =
                  latest.isPresent()
                      && latest.get().id().equals(candidate.sentence().id())
                      && latest.get().state().equals("ACTIVE");
              if (!MiningRules.finalised(
                  event.isCancelled(),
                  event.getBlock().getType().isAir(),
                  active,
                  sameSession(p))) {
                reserved.remove(pos);
                return;
              }
              QuarryConfiguration config = quarry;
              if (config == null || config != candidate.configuration()) {
                reserved.remove(pos);
                return;
              }
              Regeneration row =
                  new Regeneration(
                      pos,
                      config.regenerated().name(),
                      System.currentTimeMillis() + config.delayTicks() * 50L);
              repository
                  .credit(candidate.sentence(), row)
                  .thenCompose(credit -> service.refresh().thenApply(v -> credit))
                  .whenComplete(
                      (credit, error) ->
                          main(
                              () -> {
                                if (error != null) {
                                  fail(
                                      p,
                                      candidate.sentence(),
                                      "Mining progress could not be committed",
                                      error);
                                  return;
                                }
                                if (!credit.accepted()) {
                                  reserved.remove(pos);
                                  return;
                                }
                                queueRegeneration(row);
                                if (sameSession(p)) {
                                  int remaining =
                                      Math.max(0, credit.required() - credit.completed());
                                  if (remaining > 0 && remaining % config.milestoneInterval() == 0)
                                    p.sendMessage(
                                        message(
                                            "service-milestone",
                                            Component.text(
                                                remaining + " blocks remaining. Keep going!",
                                                NamedTextColor.YELLOW),
                                            "remaining",
                                            Integer.toString(remaining)));
                                  reconcile(p);
                                }
                              }));
            });
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void blockDrops(BlockDropItemEvent e) {
    if (restricted(e.getPlayer().getUniqueId())
        || protectedFromBuilder(e.getPlayer(), e.getBlock().getLocation())) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void place(BlockPlaceEvent e) {
    if (restricted(e.getPlayer().getUniqueId())
        || protectedFromBuilder(e.getPlayer(), e.getBlockPlaced().getLocation()))
      e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void teleportEvent(PlayerTeleportEvent e) {
    if (!restricted(e.getPlayer().getUniqueId())) return;
    Location permitted = allowedTeleports.get(e.getPlayer().getUniqueId());
    if (permitted == null || e.getTo() == null || !at(e.getTo(), permitted)) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void finaliseTeleport(PlayerTeleportEvent e) {
    if (!restricted(e.getPlayer().getUniqueId())
        || allowedTeleports.containsKey(e.getPlayer().getUniqueId())) return;
    Location original = e.getFrom().clone();
    main(
        () -> {
          if (!e.isCancelled()
              && sameSession(e.getPlayer())
              && restricted(e.getPlayer().getUniqueId())
              && !processing.contains(e.getPlayer().getUniqueId())) {
            Location rescue =
                prepared.contains(e.getPlayer().getUniqueId())
                        && quarry != null
                        && QuarryConfiguration.safe(quarry.arrival())
                    ? quarry.arrival()
                    : original;
            if (QuarryConfiguration.safe(rescue)) teleport(e.getPlayer(), rescue);
          }
        });
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void move(PlayerMoveEvent e) {
    if (e instanceof PlayerTeleportEvent
        || !restricted(e.getPlayer().getUniqueId())
        || e.getTo() == null) return;
    if (!service.healthy()
        || quarry == null
        || !prepared.contains(e.getPlayer().getUniqueId())
        || !quarry.containsPrisoner(e.getTo())) {
      Location from = e.getFrom(), to = e.getTo();
      if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ())
        e.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void command(PlayerCommandPreprocessEvent e) {
    if (!restricted(e.getPlayer().getUniqueId())) return;
    String text = e.getMessage().substring(1).toLowerCase(Locale.ROOT).strip();
    String[] words = text.split("\\s+");
    String root = words[0];
    int namespace = root.lastIndexOf(':');
    if (namespace >= 0) root = root.substring(namespace + 1);
    String normal =
        root
            + (words.length > 1
                ? " " + String.join(" ", Arrays.copyOfRange(words, 1, words.length))
                : "");
    List<String> allowed =
        quarry == null ? List.of("service progress", "help", "rules") : quarry.safeCommands();
    boolean permitted =
        allowed.stream().anyMatch(a -> normal.equals(a) || normal.startsWith(a + " "));
    if (!permitted) {
      e.setCancelled(true);
      e.getPlayer()
          .sendMessage(
              message(
                  "service-command-blocked",
                  Component.text(
                      "That command is unavailable during Community Service.",
                      NamedTextColor.RED)));
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void gameMode(PlayerGameModeChangeEvent e) {
    if (restricted(e.getPlayer().getUniqueId()) && !mutating.contains(e.getPlayer().getUniqueId()))
      e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void drop(PlayerDropItemEvent e) {
    if (restricted(e.getPlayer().getUniqueId()) || isPrisonItem(e.getItemDrop().getItemStack()))
      e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void pickup(EntityPickupItemEvent e) {
    if ((e.getEntity() instanceof Player p && restricted(p.getUniqueId()))
        || isPrisonItem(e.getItem().getItemStack())) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void swap(PlayerSwapHandItemsEvent e) {
    if (restricted(e.getPlayer().getUniqueId())) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void inventoryOpen(InventoryOpenEvent e) {
    if (restricted(e.getPlayer().getUniqueId())) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void inventoryClick(InventoryClickEvent e) {
    if (restricted(e.getWhoClicked().getUniqueId())
        || isPrisonItem(e.getCurrentItem())
        || isPrisonItem(e.getCursor())) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void inventoryDrag(InventoryDragEvent e) {
    if (restricted(e.getWhoClicked().getUniqueId())
        || e.getNewItems().values().stream().anyMatch(this::isPrisonItem)) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void inventoryMove(InventoryMoveItemEvent e) {
    if (isPrisonItem(e.getItem())) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void hopperPickup(InventoryPickupItemEvent e) {
    if (isPrisonItem(e.getItem().getItemStack())) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void consume(PlayerItemConsumeEvent e) {
    if (restricted(e.getPlayer().getUniqueId())) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void interact(PlayerInteractEvent e) {
    if (!restricted(e.getPlayer().getUniqueId())) return;
    if (e.getAction() != org.bukkit.event.block.Action.LEFT_CLICK_BLOCK
        || quarry == null
        || e.getClickedBlock() == null
        || !quarry.contains(e.getClickedBlock().getLocation())) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void entityInteract(PlayerInteractEntityEvent e) {
    if (restricted(e.getPlayer().getUniqueId())) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void entityInteractAt(PlayerInteractAtEntityEvent e) {
    if (restricted(e.getPlayer().getUniqueId())) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void armourStand(PlayerArmorStandManipulateEvent e) {
    if (restricted(e.getPlayer().getUniqueId())) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void mount(EntityMountEvent e) {
    if ((e.getEntity() instanceof Player p && restricted(p.getUniqueId()))
        || (e.getMount() instanceof Player mount && restricted(mount.getUniqueId())))
      e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void projectile(ProjectileLaunchEvent e) {
    if (e.getEntity().getShooter() instanceof Player p && restricted(p.getUniqueId()))
      e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void bucketFill(PlayerBucketFillEvent e) {
    if (restricted(e.getPlayer().getUniqueId())
        || protectedFromBuilder(e.getPlayer(), e.getBlock().getLocation())) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void bucketEmpty(PlayerBucketEmptyEvent e) {
    if (restricted(e.getPlayer().getUniqueId())
        || protectedFromBuilder(e.getPlayer(), e.getBlock().getLocation())) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void vehicle(VehicleEnterEvent e) {
    if (e.getEntered() instanceof Player p && restricted(p.getUniqueId())) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void damage(EntityDamageEvent e) {
    boolean prisoner = e.getEntity() instanceof Player p && restricted(p.getUniqueId());
    if (e instanceof EntityDamageByEntityEvent attack) {
      Entity source = attack.getDamager();
      if (source instanceof Projectile projectile
          && projectile.getShooter() instanceof Entity shooter) source = shooter;
      if (source instanceof Player p && restricted(p.getUniqueId())) prisoner = true;
    }
    if (prisoner) {
      e.setCancelled(true);
      if (e.getEntity() instanceof Player p
          && (e.getCause() == EntityDamageEvent.DamageCause.VOID
              || e.getCause() == EntityDamageEvent.DamageCause.SUFFOCATION)
          && quarry != null
          && QuarryConfiguration.safe(quarry.arrival())) teleport(p, quarry.arrival());
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void hunger(FoodLevelChangeEvent e) {
    if (restricted(e.getEntity().getUniqueId())) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void death(PlayerDeathEvent e) {
    if (restricted(e.getEntity().getUniqueId())) {
      e.getDrops().clear();
      e.setDroppedExp(0);
      e.setKeepInventory(true);
      e.setKeepLevel(true);
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void respawn(PlayerRespawnEvent e) {
    if (restricted(e.getPlayer().getUniqueId())) {
      if (quarry != null && QuarryConfiguration.safe(quarry.arrival()))
        e.setRespawnLocation(quarry.arrival());
      prepared.remove(e.getPlayer().getUniqueId());
      main(() -> reconcile(e.getPlayer()));
    }
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void quit(PlayerQuitEvent e) {
    prepared.remove(e.getPlayer().getUniqueId());
    hideBar(e.getPlayer());
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void explosion(EntityExplodeEvent e) {
    e.blockList().removeIf(b -> protectedBlock(b.getLocation()));
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void explosion(BlockExplodeEvent e) {
    e.blockList().removeIf(b -> protectedBlock(b.getLocation()));
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void piston(BlockPistonExtendEvent e) {
    if (e.getBlocks().stream()
        .anyMatch(
            b ->
                protectedBlock(b.getLocation())
                    || protectedBlock(b.getRelative(e.getDirection()).getLocation())))
      e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void piston(BlockPistonRetractEvent e) {
    if (e.getBlocks().stream()
        .anyMatch(
            b ->
                protectedBlock(b.getLocation())
                    || protectedBlock(b.getRelative(e.getDirection()).getLocation())))
      e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void fluid(BlockFromToEvent e) {
    if (protectedBlock(e.getBlock().getLocation()) || protectedBlock(e.getToBlock().getLocation()))
      e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void entityBlock(EntityChangeBlockEvent e) {
    if (protectedBlock(e.getBlock().getLocation())) e.setCancelled(true);
  }

  private boolean isPrisonItem(ItemStack item) {
    return item != null
        && item.hasItemMeta()
        && item.getItemMeta().getPersistentDataContainer().has(toolKey, PersistentDataType.STRING);
  }

  private Component message(String key, Component fallback, String... replacements) {
    if (messages == null) return fallback;
    Component rendered = messages.text(key, replacements);
    return rendered.equals(Component.text(key)) ? fallback : rendered;
  }

  public void adminQuarry(CommandSender sender, String operation) {
    if (!sender.hasPermission("mineplaypunishments.admin.quarry")) {
      sender.sendMessage(Component.text("You do not have permission.", NamedTextColor.RED));
      return;
    }
    String action = operation.toLowerCase(Locale.ROOT);
    if (action.equals("validate")) {
      try {
        reload();
        sender.sendMessage(
            Component.text(
                ready
                    ? "Prison and quarry validated. Only stone/air is present in the mining area;"
                        + " verify the selected stone is not server infrastructure before"
                        + " assigning prisoners."
                    : "Layout is incomplete. Set quarry corners, arrival, release and fallback;"
                        + " complete both prison corners if using a separate prison.",
                ready ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
      } catch (IllegalArgumentException e) {
        sender.sendMessage(Component.text("Quarry invalid: " + e.getMessage(), NamedTextColor.RED));
      }
      return;
    }
    if (!(sender instanceof Player p)) {
      sender.sendMessage(Component.text("Position commands must be run by a player."));
      return;
    }
    String key =
        switch (action) {
          case "setpos1" -> "pos1";
          case "setpos2" -> "pos2";
          case "setspawn" -> "arrival";
          case "setrelease" -> "release";
          case "setfallback" -> "fallback";
          default -> null;
        };
    if (key == null) {
      sender.sendMessage(
          Component.text("Use setpos1, setpos2, setspawn, setrelease, setfallback or validate."));
      return;
    }
    Location location = p.getLocation();
    String path = "community-service.quarry." + key;
    if (key.startsWith("pos")) {
      Block selected = p.getTargetBlockExact(6);
      if (selected == null) {
        sender.sendMessage(Component.text("Look at a quarry corner block within six blocks."));
        return;
      }
      location = selected.getLocation();
      String configured = plugin.getConfig().getString("community-service.quarry.world");
      if (configured != null
          && !configured.equals(location.getWorld().getName())
          && plugin
              .getConfig()
              .isConfigurationSection(
                  "community-service.quarry." + (key.equals("pos1") ? "pos2" : "pos1"))) {
        sender.sendMessage(
            Component.text(
                "Both quarry corners must be in the same world. Clear old corners in config"
                    + " first."));
        return;
      }
      plugin.getConfig().set("community-service.quarry.world", location.getWorld().getName());
      plugin.getConfig().set(path + ".x", location.getBlockX());
      plugin.getConfig().set(path + ".y", location.getBlockY());
      plugin.getConfig().set(path + ".z", location.getBlockZ());
    } else {
      if (!QuarryConfiguration.safe(location)) {
        sender.sendMessage(Component.text("Stand on a solid floor with clear headroom."));
        return;
      }
      plugin.getConfig().set(path + ".world", location.getWorld().getName());
      plugin.getConfig().set(path + ".x", location.getX());
      plugin.getConfig().set(path + ".y", location.getY());
      plugin.getConfig().set(path + ".z", location.getZ());
      plugin.getConfig().set(path + ".yaw", location.getYaw());
      plugin.getConfig().set(path + ".pitch", location.getPitch());
    }
    plugin.saveConfig();
    ready = false;
    quarry = null;
    quarryChunks.close();
    protection = PrisonProtection.read(plugin.getConfig());
    sender.sendMessage(
        Component.text(
            "Saved " + key + ". Run /mp admin quarry validate after configuring all positions.",
            NamedTextColor.GREEN));
  }

  public void adminPrison(CommandSender sender, String operation) {
    if (!sender.hasPermission("mineplaypunishments.admin.quarry")) {
      sender.sendMessage(Component.text("You do not have permission.", NamedTextColor.RED));
      return;
    }
    String action = operation.toLowerCase(Locale.ROOT);
    if (action.equals("validate")) {
      adminQuarry(sender, action);
      return;
    }
    String base = "community-service.prison";
    if (action.equals("copyquarry")) {
      ConfigurationSection source =
          plugin.getConfig().getConfigurationSection("community-service.quarry");
      if (source == null
          || source.getString("world") == null
          || !source.isConfigurationSection("pos1")
          || !source.isConfigurationSection("pos2")
          || java.util.stream.Stream.of("pos1.x", "pos1.y", "pos1.z", "pos2.x", "pos2.y", "pos2.z")
              .anyMatch(path -> !source.isInt(path))) {
        sender.sendMessage(
            Component.text(
                "Set both quarry corners first before copying them to the prison boundary.",
                NamedTextColor.RED));
        return;
      }
      plugin.getConfig().set(base + ".world", source.getString("world"));
      for (String corner : List.of("pos1", "pos2"))
        for (String axis : List.of("x", "y", "z"))
          plugin
              .getConfig()
              .set(base + "." + corner + "." + axis, source.getInt(corner + "." + axis));
    } else if (action.equals("setpos1") || action.equals("setpos2")) {
      if (!(sender instanceof Player player)) {
        sender.sendMessage(Component.text("Position commands must be run by a player."));
        return;
      }
      Block selected = player.getTargetBlockExact(6);
      if (selected == null) {
        sender.sendMessage(
            Component.text("Look at a prison boundary corner block within six blocks."));
        return;
      }
      String key = action.equals("setpos1") ? "pos1" : "pos2";
      String configured =
          plugin
              .getConfig()
              .getString(
                  base + ".world", plugin.getConfig().getString("community-service.quarry.world"));
      Location location = selected.getLocation();
      if (configured != null
          && !configured.equals(location.getWorld().getName())
          && plugin
              .getConfig()
              .isConfigurationSection(base + "." + (key.equals("pos1") ? "pos2" : "pos1"))) {
        sender.sendMessage(
            Component.text(
                "Both prison corners must be in the same world. Clear old prison corners in config"
                    + " first."));
        return;
      }
      plugin.getConfig().set(base + ".world", location.getWorld().getName());
      plugin.getConfig().set(base + "." + key + ".x", location.getBlockX());
      plugin.getConfig().set(base + "." + key + ".y", location.getBlockY());
      plugin.getConfig().set(base + "." + key + ".z", location.getBlockZ());
    } else {
      sender.sendMessage(Component.text("Use setpos1, setpos2, copyquarry or validate."));
      return;
    }
    plugin.saveConfig();
    ready = false;
    quarry = null;
    quarryChunks.close();
    protection = PrisonProtection.read(plugin.getConfig());
    sender.sendMessage(
        Component.text(
            "Saved prison boundary. Include every floor and walkway, then select only the stone"
                + " mining area with /mp admin quarry setpos1 and setpos2. Run /mp admin prison"
                + " validate when ready.",
            NamedTextColor.GREEN));
  }
}
