package cloud.mineplay.punishments.commands;

import cloud.mineplay.punishments.MineplayPunishments;
import cloud.mineplay.punishments.permissions.StaffHierarchy;
import cloud.mineplay.punishments.punishment.Models.*;
import com.google.gson.JsonObject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import picocli.CommandLine;

public final class ModerationCommands implements CommandExecutor, TabCompleter {
  private final MineplayPunishments plugin;

  public ModerationCommands(MineplayPunishments plugin) {
    this.plugin = plugin;
  }

  public void register() {
    for (String name :
        List.of(
            "ban",
            "tempban",
            "unban",
            "mute",
            "tempmute",
            "unmute",
            "warn",
            "kick",
            "service",
            "history",
            "warnings",
            "checkban",
            "checkmute",
            "mp")) {
      var command =
          java.util.Objects.requireNonNull(plugin.getCommand(name), "Missing command " + name);
      command.setExecutor(this);
      command.setTabCompleter(this);
    }
  }

  @Override
  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    try {
      String name = Arguments.bareCommand(command.getName());
      if (name.equals("warns")) name = "warnings";
      var parsed = CommandGrammar.parser(name).parseArgs(Arguments.tokens(String.join(" ", args)));
      String parent = name;
      boolean silent = parsed.matchedOptionValue("--silent", false);
      boolean silentOption = parsed.hasMatchedOption("--silent");
      while (parsed.hasSubcommand()) {
        parent = parsed.commandSpec().name();
        parsed = parsed.subcommand();
        silent |= parsed.matchedOptionValue("--silent", false);
        silentOption |= parsed.hasMatchedOption("--silent");
      }
      String leaf = parsed.commandSpec().name();
      if (name.equals("service") && leaf.equals("progress") && silentOption)
        throw new IllegalArgumentException("Silent mode is only available for punishment actions");
      boolean recoveryCommand =
          name.equals("mp")
              && parent.equals("admin")
              && List.of("status", "reload", "sync").contains(leaf);
      if (!plugin.punishments().healthy() && !recoveryCommand)
        throw new IllegalArgumentException("Moderation database is unavailable; action refused");
      if (name.equals("mp")) admin(sender, parent, leaf, parsed);
      else if (name.equals("service")) service(sender, leaf, parsed, silent);
      else ordinary(sender, name, parsed, silent);
    } catch (IllegalArgumentException | CommandLine.ParameterException ex) {
      error(sender, ex.getMessage());
    }
    return true;
  }

  private void ordinary(
      CommandSender sender, String command, CommandLine.ParseResult args, boolean silent) {
    String target = value(args, 0);
    if (command.equals("history") || command.equals("warnings")) {
      records(sender, command.equals("warnings"), target, args.matchedOptionValue("--page", 1));
      return;
    }
    if (command.equals("checkban") || command.equals("checkmute")) {
      require(sender, command);
      resolve(
          sender,
          target,
          id -> {
            require(sender, command);
            var active =
                plugin.punishments().active(id, command.equals("checkban") ? Type.BAN : Type.MUTE);
            success(
                sender,
                active
                    .map(
                        p ->
                            p.reference()
                                + ": "
                                + p.reason()
                                + "; expires "
                                + (p.expiresAt() == null ? "never" : p.expiresAt()))
                    .orElse("No active punishment"));
          });
      return;
    }
    require(sender, command);
    Type type =
        switch (command) {
          case "ban", "tempban", "unban" -> Type.BAN;
          case "mute", "tempmute", "unmute" -> Type.MUTE;
          case "warn" -> Type.WARNING;
          case "kick" -> Type.KICK;
          default -> throw new IllegalArgumentException("Unsupported moderation command");
        };
    boolean revoke = command.startsWith("un");
    if (!revoke) enabled(type);
    boolean temporary = command.startsWith("temp");
    Duration duration = temporary ? Arguments.duration(value(args, 1)) : null;
    String reason = reason(args, temporary ? 2 : 1, revoke ? "Revoked by staff" : null);
    guarded(
        sender,
        target,
        command,
        id -> {
          JsonObject payload = new JsonObject();
          payload.addProperty("reason", reason);
          UUID punishmentId = UUID.randomUUID();
          Long revision = null;
          ActionType actionType;
          if (revoke) {
            Punishment existing =
                plugin
                    .punishments()
                    .active(id, type)
                    .orElseThrow(() -> new IllegalArgumentException("No active " + type));
            punishmentId = existing.id();
            revision = existing.revision();
            actionType = ActionType.REVOKE_PUNISHMENT;
          } else {
            actionType = ActionType.valueOf("CREATE_" + type.name());
            if (duration != null)
              payload.addProperty("expiresAt", Instant.now().plus(duration).toString());
            if (type == Type.WARNING
                && plugin.getConfig().getInt("punishments.warnings.expiration-days") > 0)
              payload.addProperty(
                  "expiresAt",
                  Instant.now()
                      .plus(
                          Duration.ofDays(
                              plugin.getConfig().getInt("punishments.warnings.expiration-days")))
                      .toString());
          }
          apply(sender, id, punishmentId, revision, actionType, payload, silent);
        });
  }

  private void records(CommandSender sender, boolean warnings, String target, int page) {
    if (page < 1) throw new IllegalArgumentException("Page must be a positive whole number");
    requireRecords(sender, warnings, false);
    resolve(
        sender,
        target,
        id -> {
          boolean others = !(sender instanceof Player player) || !player.getUniqueId().equals(id);
          requireRecords(sender, warnings, false);
          if (others) requireRecords(sender, warnings, true);
          var request =
              warnings
                  ? plugin.punishments().warnings(id, page, 10)
                  : plugin.punishments().history(id, page, 10);
          request.whenComplete(
              (history, failure) ->
                  plugin.onMain(
                      () -> {
                        // Permissions can change while resolving names or loading persisted
                        // records.
                        if (!canReadRecords(sender, warnings, false)
                            || (others && !canReadRecords(sender, warnings, true))) return;
                        if (failure != null) {
                          error(
                              sender,
                              warnings
                                  ? "Warnings could not be loaded"
                                  : "History could not be loaded");
                          return;
                        }
                        if (history.total() == 0) {
                          success(
                              sender, warnings ? "No warning records" : "No punishment records");
                          return;
                        }
                        if (history.entries().isEmpty()) {
                          error(
                              sender,
                              "Page is out of range; there are " + history.totalPages() + " pages");
                          return;
                        }
                        String name = warnings ? "warnings" : "history";
                        success(
                            sender,
                            (warnings ? "Warnings" : "History")
                                + " for "
                                + id
                                + ": page "
                                + history.page()
                                + " of "
                                + history.totalPages()
                                + " ("
                                + history.total()
                                + " records)");
                        for (Punishment punishment : history.entries()) {
                          sender.sendMessage(
                              plugin
                                  .messages()
                                  .text(
                                      "history-entry",
                                      "reference",
                                      punishment.reference(),
                                      "type",
                                      punishment.type().name(),
                                      "status",
                                      punishment.status().name(),
                                      "issued",
                                      punishment.issuedAt().toString(),
                                      "reason",
                                      punishment.reason()));
                        }
                        if (history.hasNext()) {
                          success(
                              sender,
                              "Next page: /mineplaypunishments:"
                                  + name
                                  + " "
                                  + id
                                  + " --page "
                                  + (history.page() + 1));
                        }
                      }));
        });
  }

  private static boolean canReadRecords(CommandSender sender, boolean warnings, boolean others) {
    String suffix = others ? ".others" : "";
    return (warnings && has(sender, "warnings" + suffix)) || has(sender, "history" + suffix);
  }

  private static void requireRecords(CommandSender sender, boolean warnings, boolean others) {
    if (!canReadRecords(sender, warnings, others))
      throw new IllegalArgumentException("You do not have permission for this action");
  }

  private void service(
      CommandSender sender, String leaf, CommandLine.ParseResult args, boolean silent) {
    String target = value(args, 0);
    if (leaf.equals("progress")) {
      resolve(
          sender,
          target,
          id -> {
            if (!(sender instanceof Player p) || !p.getUniqueId().equals(id))
              require(sender, "service.progress.others");
            success(sender, plugin.community().progress(id));
          });
      return;
    }
    String permission =
        switch (leaf) {
          case "release" -> "service.release";
          case "add" -> "service.modify";
          default -> "service.issue";
        };
    require(sender, permission);
    if (!leaf.equals("release") && !plugin.community().ready())
      throw new IllegalArgumentException(
          "Community Service is unavailable; configure and validate the quarry first");
    if (target == null)
      throw new IllegalArgumentException(
          "Use /service <player> <blocks> <reason> [--tool wooden|stone] [-s]");
    JsonObject payload = new JsonObject();
    payload.addProperty(
        "reason",
        reason(
            args,
            leaf.equals("release") ? 1 : 2,
            leaf.equals("release") ? "Released by staff" : null));
    if (!leaf.equals("release")) {
      int blocks;
      try {
        blocks = Integer.parseInt(value(args, 1));
      } catch (RuntimeException ex) {
        throw new IllegalArgumentException("Blocks must be a whole number");
      }
      if (blocks < plugin.settings().minimumBlocks() || blocks > plugin.settings().maximumBlocks())
        throw new IllegalArgumentException("Blocks must be within configured limits");
      payload.addProperty("blocks", blocks);
      String configured =
          plugin
              .getConfig()
              .getString("community-service.equipment.default-pickaxe", "WOODEN_PICKAXE");
      String tool = args.matchedOptionValue("--tool", configured);
      tool =
          switch (tool.toLowerCase(Locale.ROOT)) {
            case "wooden", "wooden_pickaxe" -> "WOODEN_PICKAXE";
            case "stone", "stone_pickaxe" -> "STONE_PICKAXE";
            default -> throw new IllegalArgumentException("Tool must be wooden or stone");
          };
      if (!plugin
          .getConfig()
          .getStringList("community-service.equipment.allowed-pickaxes")
          .contains(tool)) throw new IllegalArgumentException("That tool is disabled");
      if (!leaf.equals("add")) payload.addProperty("tool", tool);
    }
    guarded(
        sender,
        target,
        permission,
        id -> {
          if (!leaf.equals("release") && !plugin.community().ready())
            throw new IllegalArgumentException(
                "Quarry configuration changed; retry after validation");
          if (payload.has("tool")
              && !plugin
                  .getConfig()
                  .getStringList("community-service.equipment.allowed-pickaxes")
                  .contains(payload.get("tool").getAsString()))
            throw new IllegalArgumentException("That tool is now disabled");
          UUID punishmentId = UUID.randomUUID();
          Long revision = null;
          ActionType type = ActionType.CREATE_COMMUNITY_SERVICE;
          if (leaf.equals("release") || leaf.equals("add")) {
            var sentence =
                plugin
                    .punishments()
                    .sentence(id)
                    .orElseThrow(
                        () ->
                            new IllegalArgumentException(
                                leaf.equals("add")
                                    ? "No active Community Service sentence. /service add only"
                                        + " extends an existing sentence. Use /service <player>"
                                        + " <blocks> <reason> or /service issue <player> <blocks>"
                                        + " <reason> to issue one."
                                    : "No active Community Service sentence to release"));
            var punishment = plugin.punishments().find(sentence.punishmentId()).orElseThrow();
            punishmentId = punishment.id();
            revision = punishment.revision();
            type =
                leaf.equals("release") ? ActionType.RELEASE_SERVICE : ActionType.ADD_SERVICE_BLOCKS;
          }
          apply(sender, id, punishmentId, revision, type, payload, silent);
        });
  }

  private void admin(
      CommandSender sender, String parent, String leaf, CommandLine.ParseResult args) {
    if (leaf.equals("acknowledge")) {
      if (!(sender instanceof Player player))
        throw new IllegalArgumentException("Only the warned player can acknowledge a warning");
      UUID id = uuid(value(args, 0));
      plugin
          .punishments()
          .acknowledgeWarning(player.getUniqueId(), id)
          .whenComplete(
              (ignored, error) ->
                  plugin.onMain(
                      () -> {
                        if (error != null)
                          error(sender, "Warning not found or acknowledgement failed");
                        else success(sender, "Warning acknowledged");
                      }));
      return;
    }
    if (leaf.equals("appeal")) {
      require(sender, "appeal");
      var p =
          plugin
              .punishments()
              .find(uuid(value(args, 0)))
              .orElseThrow(() -> new IllegalArgumentException("Punishment not found"));
      if (args.hasMatchedOption("--rotate")) {
        JsonObject payload = new JsonObject();
        payload.add("appealUrl", com.google.gson.JsonNull.INSTANCE);
        apply(
            sender,
            p.targetId(),
            p.id(),
            p.revision(),
            ActionType.ROTATE_APPEAL_TOKEN,
            payload,
            false);
        return;
      }
      sender.sendMessage(plugin.messages().punishment(p.type() == Type.BAN ? "ban" : "mute", p));
      return;
    }
    if (!parent.equals("admin"))
      throw new IllegalArgumentException(
          "Use /mp admin status|sync|reload|recover|quarry|prison, /mp acknowledge <id>, or /mp"
              + " appeal <id>");
    require(sender, "admin");
    switch (leaf) {
      case "status" ->
          success(
              sender,
              "Database: "
                  + (plugin.punishments().healthy() ? "healthy" : "unavailable")
                  + "; quarry: "
                  + (plugin.community().ready() ? "ready" : "disabled/recovery only")
                  + "; sync: "
                  + plugin.sync().status());
      case "sync" -> {
        require(sender, "admin.sync");
        plugin.sync().trigger();
        success(sender, "Synchronisation queued");
      }
      case "reload" -> {
        require(sender, "admin.reload");
        plugin.reloadSettings();
        success(sender, "Configuration reloaded");
      }
      case "quarry" -> {
        require(sender, "admin.quarry");
        plugin.community().adminQuarry(sender, value(args, 0));
      }
      case "prison" -> {
        require(sender, "admin.quarry");
        plugin.community().adminPrison(sender, value(args, 0));
      }
      case "recover" -> {
        require(sender, "admin.recover");
        resolve(
            sender,
            value(args, 0),
            id -> {
              require(sender, "admin.recover");
              Player player = Bukkit.getPlayer(id);
              if (player == null)
                throw new IllegalArgumentException("Player must be online for a verified recovery");
              plugin.community().recover(player, sender);
            });
      }
      default -> throw new IllegalArgumentException("Unknown admin operation");
    }
  }

  private void apply(
      CommandSender sender,
      UUID target,
      UUID punishment,
      Long revision,
      ActionType type,
      JsonObject payload,
      boolean silent) {
    String actorName = sender.getName();
    Player online = Bukkit.getPlayer(target);
    String targetName = online == null ? null : online.getName();
    String actor = sender instanceof Player p ? p.getUniqueId().toString() : "CONSOLE";
    Source source = sender instanceof Player ? Source.IN_GAME : Source.CONSOLE;
    Instant deadline =
        type == ActionType.CREATE_KICK
            ? Instant.now()
                .plusSeconds(
                    plugin.getConfig().getInt("punishments.kicks.remote-command-expiry-seconds"))
            : null;
    Action action =
        new Action(
            UUID.randomUUID(),
            type,
            target,
            targetName,
            punishment,
            revision,
            actor,
            source,
            Instant.now(),
            deadline,
            payload);
    plugin
        .punishments()
        .apply(action)
        .whenComplete(
            (result, failure) ->
                plugin.onMain(
                    () -> {
                      if (failure != null) {
                        plugin.logFailure("moderation_commit", failure);
                        error(
                            sender,
                            "Action could not be committed; nothing was reported as successful");
                        return;
                      }
                      if (!result.applied()) {
                        error(sender, result.code() + ": " + result.detail());
                        return;
                      }
                      success(
                          sender,
                          result.punishment().reference()
                              + " saved."
                              + (type == ActionType.CREATE_KICK
                                  ? " Kick delivery will be recorded separately."
                                  : ""));
                      try {
                        plugin.enforcement().enforce(target);
                      } catch (RuntimeException enforcementFailure) {
                        plugin.logFailure("moderation_post_commit_enforcement", enforcementFailure);
                      }
                      try {
                        plugin.sync().trigger();
                      } catch (RuntimeException syncFailure) {
                        plugin.logFailure("moderation_post_commit_sync", syncFailure);
                      }
                      if (type != ActionType.ROTATE_APPEAL_TOKEN)
                        plugin.announcements().announce(action, result, actorName, silent);
                    }));
  }

  private void guarded(CommandSender sender, String raw, String permission, Consumer<UUID> action) {
    resolve(
        sender,
        raw,
        id ->
            new StaffHierarchy(plugin, plugin.settings())
                .mayPunish(sender, id)
                .whenComplete(
                    (allowed, failure) ->
                        plugin.onMain(
                            () -> {
                              try {
                                require(sender, permission);
                                if (failure != null || !Boolean.TRUE.equals(allowed))
                                  throw new IllegalArgumentException(
                                      "Target rank is protected or cannot be verified. An"
                                          + " authorised staff override is required.");
                                action.accept(id);
                              } catch (IllegalArgumentException ex) {
                                error(sender, ex.getMessage());
                              }
                            })));
  }

  private void resolve(CommandSender sender, String raw, Consumer<UUID> action) {
    if (raw == null) {
      if (sender instanceof Player p) {
        action.accept(p.getUniqueId());
        return;
      }
      throw new IllegalArgumentException("Console must specify a player UUID or verified username");
    }
    Player online = Bukkit.getPlayerExact(raw);
    if (online != null) {
      action.accept(online.getUniqueId());
      return;
    }
    plugin
        .punishments()
        .resolve(raw)
        .whenComplete(
            (id, failure) ->
                plugin.onMain(
                    () -> {
                      try {
                        if (failure != null || id.isEmpty())
                          throw new IllegalArgumentException(
                              "Unknown or ambiguous username. Use the verified player UUID.");
                        action.accept(id.get());
                      } catch (IllegalArgumentException ex) {
                        error(sender, ex.getMessage());
                      }
                    }));
  }

  private void enabled(Type type) {
    String key =
        switch (type) {
          case BAN -> "bans";
          case MUTE -> "mutes";
          case WARNING -> "warnings";
          case KICK -> "kicks";
          default -> throw new IllegalArgumentException();
        };
    if (!plugin.getConfig().getBoolean("punishments." + key + ".enabled"))
      throw new IllegalArgumentException("Issuing this punishment type is disabled");
  }

  private static String value(CommandLine.ParseResult args, int position) {
    return args.matchedPositionalValue(position, null);
  }

  private static String reason(CommandLine.ParseResult args, int start, String fallback) {
    String[] parts = args.matchedPositionalValue(start, new String[0]);
    String value = String.join(" ", parts).strip();
    if (value.isEmpty()) {
      if (fallback != null) return fallback;
      throw new IllegalArgumentException("A reason is required");
    }
    return value;
  }

  private static UUID uuid(String value) {
    try {
      return UUID.fromString(value);
    } catch (RuntimeException ex) {
      throw new IllegalArgumentException("A valid punishment UUID is required");
    }
  }

  private static void require(CommandSender sender, String permission) {
    if (!sender.hasPermission("mineplaypunishments." + permission))
      throw new IllegalArgumentException("You do not have permission for this action");
  }

  private void error(CommandSender sender, String message) {
    sender.sendMessage(
        plugin
            .messages()
            .text("error", "message", message == null ? "Invalid arguments" : message));
  }

  private void success(CommandSender sender, String message) {
    sender.sendMessage(plugin.messages().text("success", "message", message));
  }

  @Override
  public List<String> onTabComplete(
      CommandSender sender, Command command, String alias, String[] args) {
    String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
    String name = Arguments.bareCommand(command.getName());
    if (name.equals("warns")) name = "warnings";
    if (!name.equals("mp")
        && !java.util.Arrays.asList(args).contains("--")
        && prefix.startsWith("-")) {
      List<String> options = optionCompletions(sender, name, args);
      return options.stream().filter(option -> option.startsWith(prefix)).toList();
    }
    args =
        java.util.Arrays.stream(args)
            .filter(argument -> !argument.equals("-s") && !argument.equals("--silent"))
            .toArray(String[]::new);
    List<String> candidates =
        switch (name) {
          case "mp" -> adminCompletions(sender, args);
          case "service" -> serviceCompletions(sender, args);
          default -> moderationCompletions(sender, name, args);
        };
    return candidates.stream()
        .filter(candidate -> candidate.toLowerCase(Locale.ROOT).startsWith(prefix))
        .distinct()
        .toList();
  }

  private List<String> optionCompletions(CommandSender sender, String name, String[] args) {
    if (name.equals("history") || name.equals("warnings")) {
      return canReadRecords(sender, name.equals("warnings"), false) ? List.of("--page") : List.of();
    }
    if (List.of("ban", "tempban", "unban", "mute", "tempmute", "unmute", "warn", "kick")
        .contains(name)) return has(sender, name) ? List.of("-s", "--silent") : List.of();
    if (name.equals("service")) {
      String first =
          java.util.Arrays.stream(args)
              .filter(argument -> !argument.startsWith("-"))
              .findFirst()
              .orElse("")
              .toLowerCase(Locale.ROOT);
      String permission =
          switch (first) {
            case "progress" -> null;
            case "release" -> "service.release";
            case "add" -> "service.modify";
            default -> "service.issue";
          };
      return permission != null && has(sender, permission) ? List.of("-s", "--silent") : List.of();
    }
    return List.of();
  }

  private List<String> adminCompletions(CommandSender sender, String[] args) {
    List<String> options = new ArrayList<>();
    if (args.length <= 1) {
      if (sender instanceof Player) options.add("acknowledge");
      if (has(sender, "admin")) options.add("admin");
      if (has(sender, "appeal")) options.add("appeal");
      return options;
    }
    if (args[0].equalsIgnoreCase("appeal")) {
      return args.length == 3 && has(sender, "appeal") ? List.of("--rotate") : List.of();
    }
    if (!args[0].equalsIgnoreCase("admin") || !has(sender, "admin")) return List.of();
    if (args.length == 2) {
      options.add("status");
      for (String operation : List.of("sync", "reload", "recover")) {
        if (has(sender, "admin." + operation)) options.add(operation);
      }
      if (has(sender, "admin.quarry")) options.addAll(List.of("quarry", "prison"));
      return options;
    }
    if (args.length == 3 && args[1].equalsIgnoreCase("quarry") && has(sender, "admin.quarry")) {
      return sender instanceof Player
          ? List.of("setpos1", "setpos2", "setspawn", "setrelease", "setfallback", "validate")
          : List.of("validate");
    }
    if (args.length == 3 && args[1].equalsIgnoreCase("prison") && has(sender, "admin.quarry")) {
      return sender instanceof Player
          ? List.of("setpos1", "setpos2", "copyquarry", "validate")
          : List.of("copyquarry", "validate");
    }
    if (args.length == 3 && args[1].equalsIgnoreCase("recover") && has(sender, "admin.recover")) {
      return onlineTargets(sender, true);
    }
    return List.of();
  }

  private List<String> serviceCompletions(CommandSender sender, String[] args) {
    if (args.length <= 1) {
      List<String> options = new ArrayList<>();
      if (sender instanceof Player || has(sender, "service.progress.others"))
        options.add("progress");
      if (has(sender, "service.release")) options.add("release");
      if (has(sender, "service.modify")) options.add("add");
      if (has(sender, "service.issue")) {
        options.add("issue");
        options.addAll(onlineTargets(sender, true));
      }
      return options;
    }
    if (args.length != 2) return List.of();
    if (args[0].equalsIgnoreCase("progress")) {
      return onlineTargets(sender, has(sender, "service.progress.others"));
    }
    if ((args[0].equalsIgnoreCase("release") && has(sender, "service.release"))
        || (args[0].equalsIgnoreCase("add") && has(sender, "service.modify"))
        || (args[0].equalsIgnoreCase("issue") && has(sender, "service.issue"))) {
      return onlineTargets(sender, true);
    }
    return List.of();
  }

  private List<String> moderationCompletions(CommandSender sender, String name, String[] args) {
    if (!List.of(
                "ban",
                "tempban",
                "unban",
                "mute",
                "tempmute",
                "unmute",
                "warn",
                "kick",
                "history",
                "warnings",
                "checkban",
                "checkmute")
            .contains(name)
        || (name.equals("warnings") ? !canReadRecords(sender, true, false) : !has(sender, name)))
      return List.of();
    if (args.length <= 1) {
      boolean records = name.equals("history") || name.equals("warnings");
      return onlineTargets(
          sender, !records || canReadRecords(sender, name.equals("warnings"), true));
    }
    if (args.length == 2 && (name.equals("tempban") || name.equals("tempmute"))) {
      return List.of("30m", "1h", "4h", "8h", "1d", "7d", "30d", "permanent");
    }
    return List.of();
  }

  private static boolean has(CommandSender sender, String permission) {
    return sender.hasPermission("mineplaypunishments." + permission);
  }

  private static List<String> onlineTargets(CommandSender sender, boolean includeOthers) {
    if (!includeOthers)
      return sender instanceof Player player ? List.of(player.getName()) : List.of();
    return Bukkit.getOnlinePlayers().stream()
        .filter(target -> !(sender instanceof Player player) || player.canSee(target))
        .map(Player::getName)
        .sorted(String.CASE_INSENSITIVE_ORDER)
        .toList();
  }
}
