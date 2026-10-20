package cloud.mineplay.punishments.commands;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cloud.mineplay.punishments.MineplayPunishments;
import cloud.mineplay.punishments.communityservice.CommunityServiceManager;
import cloud.mineplay.punishments.config.Messages;
import cloud.mineplay.punishments.config.PluginSettings;
import cloud.mineplay.punishments.enforcement.EnforcementListener;
import cloud.mineplay.punishments.integration.SyncClient;
import cloud.mineplay.punishments.punishment.Models.*;
import cloud.mineplay.punishments.punishment.PunishmentService;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ModerationCommandsTest {
  private MineplayPunishments plugin;
  private PunishmentService service;
  private CommunityServiceManager quarry;
  private ConsoleCommandSender sender;
  private ModerationCommands commands;
  private UUID target;
  private Punishment punishment;

  @BeforeEach
  void setup() {
    plugin = mock(MineplayPunishments.class);
    service = mock(PunishmentService.class);
    quarry = mock(CommunityServiceManager.class);
    sender = mock(ConsoleCommandSender.class);
    target = UUID.randomUUID();
    punishment =
        new Punishment(
            UUID.randomUUID(),
            "MP-TEST",
            target,
            "Test",
            Type.COMMUNITY_SERVICE,
            "Original",
            "CONSOLE",
            Source.CONSOLE,
            Instant.now(),
            null,
            Status.ACTIVE,
            5,
            null);
    var config =
        YamlConfiguration.loadConfiguration(
            new InputStreamReader(
                getClass().getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
    when(plugin.getConfig()).thenReturn(config);
    when(plugin.settings()).thenReturn(PluginSettings.read(config, Path.of("test-plugin")));
    when(plugin.punishments()).thenReturn(service);
    when(plugin.community()).thenReturn(quarry);
    when(plugin.messages()).thenReturn(mock(Messages.class));
    when(plugin.enforcement()).thenReturn(mock(EnforcementListener.class));
    when(plugin.sync()).thenReturn(mock(SyncClient.class));
    doAnswer(
            call -> {
              call.<Runnable>getArgument(0).run();
              return null;
            })
        .when(plugin)
        .onMain(any());
    when(sender.hasPermission(anyString())).thenReturn(true);
    when(service.healthy()).thenReturn(true);
    when(service.resolve(target.toString()))
        .thenReturn(CompletableFuture.completedFuture(Optional.of(target)));
    when(service.find(punishment.id())).thenReturn(Optional.of(punishment));
    when(service.sentence(target))
        .thenReturn(
            Optional.of(
                new Sentence(
                    UUID.randomUUID(),
                    punishment.id(),
                    target,
                    100,
                    20,
                    "WOODEN_PICKAXE",
                    "ACTIVE")));
    when(service.apply(any(Action.class)))
        .thenReturn(
            CompletableFuture.completedFuture(new Result(true, "APPLIED", "saved", punishment)));
    when(quarry.ready()).thenReturn(true);
    commands = new ModerationCommands(plugin);
  }

  private void invoke(String root, String... args) {
    Command command = mock(Command.class);
    when(command.getName()).thenReturn(root);
    try (var ignored = mockStatic(Bukkit.class)) {
      commands.onCommand(sender, command, root, args);
    }
  }

  @Test
  void addWorkEmitsOnlyAllowedPayloadAndExpectedRevision() {
    invoke("service", "add", target.toString(), "10", "Further", "damage");
    var captor = ArgumentCaptor.forClass(Action.class);
    verify(service).apply(captor.capture());
    var action = captor.getValue();
    assertEquals(ActionType.ADD_SERVICE_BLOCKS, action.type());
    assertEquals(5L, action.expectedRevision());
    assertEquals(2, action.payload().size());
    assertFalse(action.payload().has("tool"));
    assertEquals("Further damage", action.payload().get("reason").getAsString());
  }

  @Test
  void offlineServiceIssueAcceptsAnExplicitTool() {
    invoke("service", target.toString(), "100", "Repair", "damage", "--tool", "stone");
    var captor = ArgumentCaptor.forClass(Action.class);
    verify(service).apply(captor.capture());
    assertEquals(ActionType.CREATE_COMMUNITY_SERVICE, captor.getValue().type());
    assertEquals("STONE_PICKAXE", captor.getValue().payload().get("tool").getAsString());
  }

  @Test
  void explicitIssueAliasCreatesANewSentenceWithTheSamePayload() {
    when(service.sentence(target)).thenReturn(Optional.empty());
    invoke("service", "issue", target.toString(), "100", "Repair", "damage", "--tool", "stone");
    var captor = ArgumentCaptor.forClass(Action.class);
    verify(service).apply(captor.capture());
    var action = captor.getValue();
    assertEquals(ActionType.CREATE_COMMUNITY_SERVICE, action.type());
    assertNull(action.expectedRevision());
    assertEquals(target, action.targetId());
    assertEquals(100, action.payload().get("blocks").getAsInt());
    assertEquals("Repair damage", action.payload().get("reason").getAsString());
    assertEquals("STONE_PICKAXE", action.payload().get("tool").getAsString());
  }

  @Test
  void addWithoutAnActiveSentenceExplainsHowToIssueAndDoesNotCreateOne() {
    when(service.sentence(target)).thenReturn(Optional.empty());
    invoke("service", "add", target.toString(), "100", "Repair", "damage");
    verify(service, never()).apply(any(Action.class));
    verify(plugin.messages())
        .text(
            "error",
            "message",
            "No active Community Service sentence. /service add only extends an existing sentence."
                + " Use /service <player> <blocks> <reason> or /service issue <player> <blocks>"
                + " <reason> to issue one.");
  }

  @Test
  void issueAliasRequiresIssuePermissionAndCannotUseModifyPermissionInstead() {
    when(sender.hasPermission("mineplaypunishments.service.issue")).thenReturn(false);
    invoke("service", "issue", target.toString(), "100", "Repair", "damage");
    verify(service, never()).apply(any(Action.class));
  }

  @Test
  void earlyReleaseWorksWhenQuarryIsUnavailable() {
    when(quarry.ready()).thenReturn(false);
    invoke("service", "release", target.toString(), "Emergency");
    var captor = ArgumentCaptor.forClass(Action.class);
    verify(service).apply(captor.capture());
    assertEquals(ActionType.RELEASE_SERVICE, captor.getValue().type());
  }

  @Test
  void permissionDenialCannotReachStorageMutation() {
    when(sender.hasPermission("mineplaypunishments.ban")).thenReturn(false);
    invoke("ban", target.toString(), "Reason");
    verify(service, never()).apply(any(Action.class));
  }

  @Test
  void unverifiedStoreRejectsModerationButAllowsStatus() {
    when(service.healthy()).thenReturn(false);
    invoke("ban", target.toString(), "Reason");
    verify(service, never()).apply(any(Action.class));
    invoke("mp", "admin", "status");
    verify(plugin.sync()).status();
  }

  @Test
  void quarrySetupOperationsDispatchThroughNamespacedAlias() {
    Command command = mock(Command.class);
    when(command.getName()).thenReturn("mp");
    for (String operation :
        List.of("setpos1", "setpos2", "setspawn", "setrelease", "setfallback", "validate")) {
      commands.onCommand(
          sender, command, "mineplaypunishments:mp", new String[] {"admin", "quarry", operation});
      verify(quarry).adminQuarry(sender, operation);
    }
    verify(service, never()).apply(any(Action.class));
  }

  @Test
  void quarryDispatchRequiresParentAndChildPermissions() {
    when(sender.hasPermission("mineplaypunishments.admin")).thenReturn(false);
    invoke("mp", "admin", "quarry", "validate");
    verify(quarry, never()).adminQuarry(any(), anyString());
    when(sender.hasPermission("mineplaypunishments.admin")).thenReturn(true);
    when(sender.hasPermission("mineplaypunishments.admin.quarry")).thenReturn(false);
    invoke("mp", "admin", "quarry", "setpos1");
    verify(quarry, never()).adminQuarry(any(), anyString());
  }

  @Test
  void prisonOperationsDispatchThroughNamespacedAlias() {
    Command command = mock(Command.class);
    when(command.getName()).thenReturn("mp");
    for (String operation : List.of("setpos1", "setpos2", "copyquarry", "validate")) {
      commands.onCommand(
          sender, command, "mineplaypunishments:mp", new String[] {"admin", "prison", operation});
      verify(quarry).adminPrison(sender, operation);
    }
    verify(quarry, never()).adminQuarry(any(), anyString());
    verify(service, never()).apply(any(Action.class));
  }

  @Test
  void prisonDispatchRequiresBothExistingAdminAndQuarryPermissions() {
    when(sender.hasPermission("mineplaypunishments.admin")).thenReturn(false);
    invoke("mp", "admin", "prison", "copyquarry");
    verify(quarry, never()).adminPrison(any(), anyString());
    when(sender.hasPermission("mineplaypunishments.admin")).thenReturn(true);
    when(sender.hasPermission("mineplaypunishments.admin.quarry")).thenReturn(false);
    invoke("mp", "admin", "prison", "validate");
    verify(quarry, never()).adminPrison(any(), anyString());
  }
}
