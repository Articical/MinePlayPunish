package cloud.mineplay.punishments.commands;

import cloud.mineplay.punishments.MineplayPunishments;
import cloud.mineplay.punishments.config.PluginSettings;
import cloud.mineplay.punishments.permissions.StaffHierarchy;
import cloud.mineplay.punishments.punishment.Models.Action;
import cloud.mineplay.punishments.punishment.Models.ActionType;
import cloud.mineplay.punishments.punishment.Models.Result;
import cloud.mineplay.punishments.punishment.Models.Source;
import cloud.mineplay.punishments.punishment.Models.Type;
import com.google.gson.JsonObject;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Main-thread gateway for the staff GUI. UI input is never authority: permission, custody, target
 * hierarchy, current settings and the session guard are checked immediately before enqueueing a
 * durable action. Completion callbacks run on the main thread while the plugin is enabled.
 */
public final class StaffModeration {
  private static final String GUI_PERMISSION = "mineplaypunishments.gui";
  private static final int MAX_REMEMBERED_SUBMISSIONS = 256;
  private static final Duration MAX_DURATION = Duration.ofDays(100L * 366);

  public record Target(UUID id, String name) {}

  public record Resolution(Target target, String code, String detail) {
    public boolean resolved() {
      return target != null;
    }
  }

  private record ServiceWork(int blocks, String tool) {}

  private record Request(
      UUID actor,
      UUID target,
      Type type,
      Duration duration,
      String reason,
      ServiceWork serviceWork,
      boolean silent) {}

  private static final class Submission {
    private final Request request;
    private final String actorName;
    private CompletableFuture<Result> completion = new CompletableFuture<>();
    private Action action;
    private boolean retryable;

    private Submission(Request request, String actorName) {
      this.request = request;
      this.actorName = actorName;
    }
  }

  private final MineplayPunishments plugin;
  private final Clock clock;
  // Main-thread only. In-flight submissions are never evicted. SQLite remains the durable ledger.
  private final Map<UUID, Submission> submissions = new LinkedHashMap<>();

  public StaffModeration(MineplayPunishments plugin) {
    this(plugin, Clock.systemUTC());
  }

  StaffModeration(MineplayPunishments plugin, Clock clock) {
    this.plugin = Objects.requireNonNull(plugin, "plugin");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Rendering eligibility only; target hierarchy is checked during resolution and submission. */
  public boolean available(Player actor, Type type, Duration duration) {
    requireMainThread();
    try {
      checkAccess(actor);
      checkType(actor, type, duration);
      return true;
    } catch (Refused refusal) {
      return false;
    }
  }

  /** Community Service has a separate entry point so it cannot be issued without a block count. */
  public boolean availableService(Player actor) {
    return serviceUnavailableReason(actor) == null;
  }

  /** The same admission checks as submission, with the specific reason for a disabled GUI item. */
  public String serviceUnavailableReason(Player actor) {
    requireMainThread();
    try {
      checkAccess(actor);
      checkService(actor, plugin.settings().minimumBlocks());
      return null;
    } catch (Refused refusal) {
      return refusal.getMessage();
    }
  }

  /** Accepts a verified online/previously-seen name or a canonical UUID, never an invented UUID. */
  public CompletableFuture<Resolution> resolve(Player actor, String input) {
    requireMainThread();
    CompletableFuture<Resolution> completion = new CompletableFuture<>();
    String target;
    try {
      checkAccess(actor);
      if (!canIssueAnything(actor))
        throw refused("PERMISSION_DENIED", "You cannot issue any GUI punishment types");
      target = input == null ? "" : input.strip();
      if (target.isEmpty())
        throw refused("INVALID_TARGET", "Specify a player UUID or verified username");
    } catch (Refused refusal) {
      finishResolution(actor, completion, new Resolution(null, refusal.code, refusal.getMessage()));
      return completion;
    }
    Player online = Bukkit.getPlayerExact(target);
    if (online != null) {
      authorizeResolution(actor, new Target(online.getUniqueId(), online.getName()), completion);
      return completion;
    }
    plugin
        .punishments()
        .resolve(target)
        .whenComplete(
            (resolved, failure) ->
                plugin.onMain(
                    () -> {
                      if (failure != null) {
                        plugin.logFailure("gui_target_resolution", failure);
                        finishResolution(
                            actor,
                            completion,
                            new Resolution(
                                null,
                                "STORAGE_UNAVAILABLE",
                                "The target could not be verified; retry when moderation storage is"
                                    + " available"));
                      } else if (resolved == null || resolved.isEmpty()) {
                        finishResolution(
                            actor,
                            completion,
                            new Resolution(
                                null,
                                "UNKNOWN_TARGET",
                                "Unknown or ambiguous username. Use the verified player UUID"));
                      } else {
                        Player current = Bukkit.getPlayer(resolved.get());
                        authorizeResolution(
                            actor,
                            new Target(
                                resolved.get(), current == null ? target : current.getName()),
                            completion);
                      }
                    }));
    return completion;
  }

  private void authorizeResolution(
      Player actor, Target target, CompletableFuture<Resolution> completion) {
    try {
      checkAccess(actor);
      if (!canIssueAnything(actor))
        throw refused("PERMISSION_DENIED", "Your moderation permissions have changed");
    } catch (Refused refusal) {
      finishResolution(actor, completion, new Resolution(null, refusal.code, refusal.getMessage()));
      return;
    }
    PluginSettings settings = plugin.settings();
    hierarchy(actor, target.id())
        .whenComplete(
            (allowed, failure) ->
                plugin.onMain(
                    () -> {
                      try {
                        checkAccess(actor);
                        if (!canIssueAnything(actor))
                          throw refused(
                              "PERMISSION_DENIED", "Your moderation permissions have changed");
                        if (plugin.settings() != settings)
                          throw refused(
                              "CONFIGURATION_CHANGED",
                              "Moderation settings changed; reopen the punishment menu");
                        if (failure != null || !Boolean.TRUE.equals(allowed))
                          throw refused(
                              "TARGET_PROTECTED",
                              "Target rank is protected or cannot be verified. An authorised staff"
                                  + " override is required");
                        finishResolution(
                            actor,
                            completion,
                            new Resolution(target, "RESOLVED", "Target verified"));
                      } catch (Refused refusal) {
                        finishResolution(
                            actor,
                            completion,
                            new Resolution(null, refusal.code, refusal.getMessage()));
                      }
                    }));
  }

  public CompletableFuture<Result> submit(
      Player actor, UUID target, UUID actionId, Type type, Duration duration, String reason) {
    return submit(actor, target, actionId, type, duration, reason, () -> true);
  }

  /**
   * Retain actionId for the same confirmation/retry. stillValid must identify the same live GUI
   * session and return false after close/replacement/reload. Closing after the commit has been
   * enqueued does not undo an authorised punishment. A retry after a storage error reuses the exact
   * immutable Action, including issue time, expiration and punishment UUID.
   */
  public CompletableFuture<Result> submit(
      Player actor,
      UUID target,
      UUID actionId,
      Type type,
      Duration duration,
      String reason,
      BooleanSupplier stillValid) {
    return submit(actor, target, actionId, type, duration, reason, false, stillValid);
  }

  public CompletableFuture<Result> submit(
      Player actor,
      UUID target,
      UUID actionId,
      Type type,
      Duration duration,
      String reason,
      boolean silent,
      BooleanSupplier stillValid) {
    requireMainThread();
    Request request;
    try {
      checkAccess(actor);
      checkType(actor, type, duration);
      if (target == null || actionId == null)
        throw refused("INVALID_ACTION", "A target UUID and confirmation UUID are required");
      checkSession(stillValid);
      request =
          new Request(
              actor.getUniqueId(), target, type, duration, checkedReason(reason), null, silent);
    } catch (Refused refusal) {
      return immediateFailure(actor, refusal.code, refusal.getMessage());
    }
    return enqueue(actor, actionId, request, stillValid);
  }

  /**
   * Issues a new sentence using the validated configured prison tool; additional work is explicit.
   */
  public CompletableFuture<Result> submitService(
      Player actor,
      UUID target,
      UUID actionId,
      int blocks,
      String reason,
      BooleanSupplier stillValid) {
    return submitService(actor, target, actionId, blocks, reason, false, stillValid);
  }

  public CompletableFuture<Result> submitService(
      Player actor,
      UUID target,
      UUID actionId,
      int blocks,
      String reason,
      boolean silent,
      BooleanSupplier stillValid) {
    requireMainThread();
    Request request;
    try {
      checkAccess(actor);
      String tool = checkService(actor, blocks);
      if (target == null || actionId == null)
        throw refused("INVALID_ACTION", "A target UUID and confirmation UUID are required");
      checkSession(stillValid);
      request =
          new Request(
              actor.getUniqueId(),
              target,
              Type.COMMUNITY_SERVICE,
              null,
              checkedReason(reason),
              new ServiceWork(blocks, tool),
              silent);
    } catch (Refused refusal) {
      return immediateFailure(actor, refusal.code, refusal.getMessage());
    }
    return enqueue(actor, actionId, request, stillValid);
  }

  private CompletableFuture<Result> enqueue(
      Player actor, UUID actionId, Request request, BooleanSupplier stillValid) {
    Submission previous = submissions.get(actionId);
    if (previous != null) {
      if (!previous.request.equals(request))
        return immediateFailure(
            actor,
            "REPLAY_MISMATCH",
            "This confirmation was already used for another action; reopen the punishment menu");
      if (!previous.completion.isDone() || !previous.retryable) return previous.completion;
      previous.completion = new CompletableFuture<>();
      previous.retryable = false;
      authorizeSubmission(actor, actionId, previous, stillValid);
      return previous.completion;
    }
    if (!makeRoom())
      return immediateFailure(
          actor, "BUSY", "Too many moderation requests are awaiting confirmation; retry shortly");
    Submission submission = new Submission(request, actor.getName());
    submissions.put(actionId, submission);
    authorizeSubmission(actor, actionId, submission, stillValid);
    return submission.completion;
  }

  private void authorizeSubmission(
      Player actor, UUID actionId, Submission submission, BooleanSupplier stillValid) {
    PluginSettings settings = plugin.settings();
    hierarchy(actor, submission.request.target())
        .whenComplete(
            (allowed, failure) ->
                plugin.onMain(
                    () -> {
                      try {
                        checkAccess(actor);
                        checkSession(stillValid);
                        if (submission.request.serviceWork() != null) {
                          ServiceWork work = submission.request.serviceWork();
                          String tool = checkService(actor, work.blocks());
                          if (!tool.equals(work.tool()))
                            throw refused(
                                "CONFIGURATION_CHANGED",
                                "Prison equipment changed; reopen the punishment menu");
                        } else {
                          checkType(
                              actor, submission.request.type(), submission.request.duration());
                        }
                        checkedReason(submission.request.reason());
                        if (plugin.settings() != settings)
                          throw refused(
                              "CONFIGURATION_CHANGED",
                              "Moderation settings changed; reopen the punishment menu");
                        if (failure != null || !Boolean.TRUE.equals(allowed))
                          throw refused(
                              "TARGET_PROTECTED",
                              "Target rank is protected or cannot be verified. An authorised staff"
                                  + " override is required");
                        if (submission.action == null)
                          submission.action = action(actor, actionId, submission.request);
                      } catch (Refused refusal) {
                        finishSubmission(
                            actor,
                            submission,
                            new Result(false, refusal.code, refusal.getMessage(), null));
                        return;
                      }
                      plugin
                          .punishments()
                          .apply(submission.action)
                          .whenComplete(
                              (result, error) ->
                                  plugin.onMain(
                                      () -> {
                                        if (error != null || result == null) {
                                          if (error != null)
                                            plugin.logFailure("gui_moderation_commit", error);
                                          submission.retryable = true;
                                          finishSubmission(
                                              actor,
                                              submission,
                                              new Result(
                                                  false,
                                                  "STORAGE_UNAVAILABLE",
                                                  "Confirmation could not be verified. Check"
                                                      + " /history "
                                                      + submission.request.target()
                                                      + " before issuing another action",
                                                  null));
                                          return;
                                        }
                                        if (result.applied()) {
                                          try {
                                            plugin
                                                .enforcement()
                                                .enforce(submission.request.target());
                                            plugin.sync().trigger();
                                          } catch (RuntimeException enforcementFailure) {
                                            plugin.logFailure(
                                                "gui_post_commit_enforcement", enforcementFailure);
                                          }
                                          try {
                                            plugin
                                                .announcements()
                                                .announce(
                                                    submission.action,
                                                    result,
                                                    submission.actorName,
                                                    submission.request.silent());
                                          } catch (RuntimeException announcementFailure) {
                                            plugin.logFailure(
                                                "gui_announcement", announcementFailure);
                                          }
                                        }
                                        finishSubmission(actor, submission, result);
                                      }));
                    }));
  }

  private CompletableFuture<Boolean> hierarchy(Player actor, UUID target) {
    try {
      return new StaffHierarchy(plugin, plugin.settings()).mayPunish(actor, target);
    } catch (RuntimeException failure) {
      plugin.logFailure("gui_hierarchy_check", failure);
      return CompletableFuture.completedFuture(false);
    }
  }

  private Action action(Player actor, UUID actionId, Request request) {
    Instant issued = clock.instant();
    JsonObject payload = new JsonObject();
    payload.addProperty("reason", request.reason());
    if (request.serviceWork() != null) {
      payload.addProperty("blocks", request.serviceWork().blocks());
      payload.addProperty("tool", request.serviceWork().tool());
    }
    if (request.duration() != null)
      payload.addProperty("expiresAt", issued.plus(request.duration()).toString());
    if (request.type() == Type.WARNING) {
      int days = plugin.getConfig().getInt("punishments.warnings.expiration-days");
      if (days > 0) payload.addProperty("expiresAt", issued.plus(Duration.ofDays(days)).toString());
    }
    Instant deadline =
        request.type() == Type.KICK
            ? issued.plusSeconds(
                plugin.getConfig().getInt("punishments.kicks.remote-command-expiry-seconds"))
            : null;
    Player online = Bukkit.getPlayer(request.target());
    return new Action(
        actionId,
        ActionType.valueOf("CREATE_" + request.type().name()),
        request.target(),
        online == null ? null : online.getName(),
        UUID.randomUUID(),
        null,
        actor.getUniqueId().toString(),
        Source.IN_GAME,
        issued,
        deadline,
        payload);
  }

  private void checkAccess(Player actor) {
    if (actor == null || !actor.isOnline() || Bukkit.getPlayer(actor.getUniqueId()) != actor)
      throw refused("PLAYER_OFFLINE", "This staff session is no longer connected");
    if (!actor.hasPermission(GUI_PERMISSION))
      throw refused("PERMISSION_DENIED", "You do not have permission to use the punishment menu");
    if (plugin.punishments() == null || !plugin.punishments().healthy())
      throw refused("STORAGE_UNAVAILABLE", "Moderation storage is unavailable; action refused");
    if (plugin.community() == null || plugin.community().restricted(actor.getUniqueId()))
      throw refused(
          "STAFF_RESTRICTED",
          "The punishment menu is unavailable while you are serving Community Service");
  }

  private void checkType(Player actor, Type type, Duration duration) {
    String permission = permission(type, duration);
    if (!actor.hasPermission("mineplaypunishments." + permission))
      throw refused("PERMISSION_DENIED", "You do not have permission for this punishment");
    String section =
        switch (type) {
          case BAN -> "bans";
          case MUTE -> "mutes";
          case WARNING -> "warnings";
          case KICK -> "kicks";
          default ->
              throw refused("UNSUPPORTED_TYPE", "This punishment is not available in the menu");
        };
    if (!plugin.getConfig().getBoolean("punishments." + section + ".enabled"))
      throw refused("TYPE_DISABLED", "Issuing this punishment type is disabled");
  }

  private String checkService(Player actor, int blocks) {
    if (!actor.hasPermission("mineplaypunishments.service.issue"))
      throw refused("PERMISSION_DENIED", "You do not have permission to issue Community Service");
    if (!plugin.getConfig().getBoolean("community-service.enabled"))
      throw refused("TYPE_DISABLED", "Issuing Community Service is disabled");
    if (!plugin.community().ready())
      throw refused(
          "QUARRY_UNAVAILABLE",
          "Community Service is unavailable; configure and validate the quarry first");
    int minimum = plugin.settings().minimumBlocks();
    int maximum = plugin.settings().maximumBlocks();
    if (blocks < minimum || blocks > maximum)
      throw refused("INVALID_BLOCKS", "Blocks must be between " + minimum + " and " + maximum);
    String tool = plugin.getConfig().getString("community-service.equipment.default-pickaxe");
    if (!("WOODEN_PICKAXE".equals(tool) || "STONE_PICKAXE".equals(tool))
        || !plugin
            .getConfig()
            .getStringList("community-service.equipment.allowed-pickaxes")
            .contains(tool))
      throw refused(
          "INVALID_TOOL",
          "The configured prison pickaxe is not an allowed wooden or stone pickaxe");
    return tool;
  }

  public static String permission(Type type, Duration duration) {
    if (type == null || type == Type.COMMUNITY_SERVICE)
      throw refused("UNSUPPORTED_TYPE", "This punishment is not available in the menu");
    if (duration != null
        && (duration.isZero()
            || duration.isNegative()
            || duration.compareTo(MAX_DURATION) > 0
            || type != Type.BAN && type != Type.MUTE))
      throw refused(
          "INVALID_DURATION",
          "Only bans and mutes accept a positive duration of at most 100 years");
    return switch (type) {
      case BAN -> duration == null ? "ban" : "tempban";
      case MUTE -> duration == null ? "mute" : "tempmute";
      case WARNING -> "warn";
      case KICK -> "kick";
      default -> throw refused("UNSUPPORTED_TYPE", "This punishment is not available in the menu");
    };
  }

  private String checkedReason(String reason) {
    String normalized = reason == null ? "" : reason.strip();
    int limit = plugin.settings().maximumReasonLength();
    if (normalized.isBlank()
        || normalized.length() > limit
        || normalized.codePoints().anyMatch(Character::isISOControl))
      throw refused(
          "INVALID_REASON",
          "Reason must contain 1-" + limit + " characters without control characters");
    return normalized;
  }

  private boolean canIssueAnything(Player actor) {
    return available(actor, Type.BAN, null)
        || available(actor, Type.BAN, Duration.ofMinutes(1))
        || available(actor, Type.MUTE, null)
        || available(actor, Type.MUTE, Duration.ofMinutes(1))
        || available(actor, Type.WARNING, null)
        || available(actor, Type.KICK, null)
        || availableService(actor);
  }

  private static void checkSession(BooleanSupplier stillValid) {
    if (stillValid == null || !stillValid.getAsBoolean())
      throw refused("CANCELLED", "The punishment menu was closed or replaced before confirmation");
  }

  private boolean makeRoom() {
    Iterator<Submission> existing = submissions.values().iterator();
    while (submissions.size() >= MAX_REMEMBERED_SUBMISSIONS && existing.hasNext()) {
      if (existing.next().completion.isDone()) existing.remove();
    }
    return submissions.size() < MAX_REMEMBERED_SUBMISSIONS;
  }

  private void finishResolution(
      Player actor, CompletableFuture<Resolution> completion, Resolution result) {
    if (!result.resolved()) notifyError(actor, result.detail());
    completion.complete(result);
  }

  private CompletableFuture<Result> immediateFailure(Player actor, String code, String detail) {
    notifyError(actor, detail);
    return CompletableFuture.completedFuture(new Result(false, code, detail, null));
  }

  private void finishSubmission(Player actor, Submission submission, Result result) {
    if (actor.isOnline()) {
      if (result.applied()) {
        String detail =
            result.punishment().reference()
                + " saved. "
                + (submission.request.type() == Type.KICK
                    ? "Kick delivery will be recorded separately."
                    : "Enforcement will be checked automatically.");
        actor.sendMessage(plugin.messages().text("success", "message", detail));
      } else notifyError(actor, result.detail());
    }
    submission.completion.complete(result);
  }

  private void notifyError(Player actor, String detail) {
    if (actor != null && actor.isOnline())
      actor.sendMessage(plugin.messages().text("error", "message", detail));
  }

  private static void requireMainThread() {
    if (!Bukkit.isPrimaryThread())
      throw new IllegalStateException("StaffModeration must be called on the server thread");
  }

  private static Refused refused(String code, String detail) {
    return new Refused(code, detail);
  }

  private static final class Refused extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;
    private final String code;

    private Refused(String code, String detail) {
      super(detail);
      this.code = code;
    }
  }
}
