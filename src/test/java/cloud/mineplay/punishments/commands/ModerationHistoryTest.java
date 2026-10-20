package cloud.mineplay.punishments.commands;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cloud.mineplay.punishments.MineplayPunishments;
import cloud.mineplay.punishments.config.Messages;
import cloud.mineplay.punishments.punishment.Models.*;
import cloud.mineplay.punishments.punishment.PunishmentService;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

class ModerationHistoryTest {
  private final UUID ownId = UUID.randomUUID();
  private final UUID otherId = UUID.randomUUID();
  private final Set<String> permissions = new HashSet<>();
  private final List<String> output = new ArrayList<>();
  private MineplayPunishments plugin;
  private PunishmentService store;
  private Player sender;
  private ModerationCommands commands;
  private MockedStatic<Bukkit> bukkit;

  @BeforeEach
  void setup() {
    plugin = mock(MineplayPunishments.class);
    store = mock(PunishmentService.class);
    sender = mock(Player.class);
    Messages messages = mock(Messages.class);
    when(plugin.punishments()).thenReturn(store);
    when(plugin.messages()).thenReturn(messages);
    when(store.healthy()).thenReturn(true);
    when(sender.getUniqueId()).thenReturn(ownId);
    when(sender.hasPermission(anyString()))
        .thenAnswer(call -> permissions.contains(call.getArgument(0)));
    when(store.resolve(otherId.toString()))
        .thenReturn(CompletableFuture.completedFuture(Optional.of(otherId)));
    when(messages.text(anyString(), any(String[].class)))
        .thenAnswer(
            call -> {
              String text =
                  call.getArgument(0)
                      + " "
                      + String.join(" ", (String[]) call.getRawArguments()[1]);
              output.add(text);
              return Component.text(text);
            });
    doAnswer(
            call -> {
              call.<Runnable>getArgument(0).run();
              return null;
            })
        .when(plugin)
        .onMain(any());
    commands = new ModerationCommands(plugin);
    bukkit = mockStatic(Bukkit.class);
  }

  @AfterEach
  void cleanup() {
    bukkit.close();
  }

  private void grant(String... names) {
    for (String name : names) permissions.add("mineplaypunishments." + name);
  }

  private void invoke(String name, String... arguments) {
    invoke(sender, name, arguments);
  }

  private void invoke(CommandSender actor, String name, String... arguments) {
    Command command = mock(Command.class);
    when(command.getName()).thenReturn(name);
    commands.onCommand(actor, command, name, arguments);
  }

  private Punishment record(Type type, Status status, String reference) {
    return new Punishment(
        UUID.randomUUID(),
        reference,
        ownId,
        "Saved name",
        type,
        "Saved reason",
        "CONSOLE",
        Source.CONSOLE,
        Instant.parse("2026-10-01T12:00:00Z"),
        null,
        status,
        1,
        "https://www.mineplay.co.uk/appeal/secret-never-displayed");
  }

  @Test
  void warningsListsPersistedActiveExpiredAndRevokedRecordsForSelf() {
    grant("warnings");
    var rows =
        List.of(
            record(Type.WARNING, Status.ACTIVE, "MP-A"),
            record(Type.WARNING, Status.EXPIRED, "MP-B"),
            record(Type.WARNING, Status.REVOKED, "MP-C"));
    when(store.warnings(ownId, 1, 10))
        .thenReturn(CompletableFuture.completedFuture(new HistoryPage(rows, 1, 10, 3)));
    invoke("warnings");
    verify(store).warnings(ownId, 1, 10);
    verify(store, never()).history(any(), anyInt(), anyInt());
    assertEquals(3, output.stream().filter(line -> line.startsWith("history-entry ")).count());
    assertTrue(output.stream().anyMatch(line -> line.contains("type WARNING status EXPIRED")));
    assertTrue(output.stream().anyMatch(line -> line.contains("type WARNING status REVOKED")));
    assertFalse(String.join("\n", output).contains("secret-never-displayed"));
  }

  @Test
  void historyIncludesWarningTypeExplicitlyAlongsideOtherTypes() {
    grant("history");
    List<Punishment> rows = new ArrayList<>();
    for (Type type : Type.values()) rows.add(record(type, Status.ACTIVE, "MP-" + type));
    when(store.history(ownId, 1, 10))
        .thenReturn(CompletableFuture.completedFuture(new HistoryPage(rows, 1, 10, rows.size())));
    invoke("history");
    assertEquals(
        Type.values().length,
        output.stream().filter(line -> line.startsWith("history-entry ")).count());
    assertTrue(output.stream().anyMatch(line -> line.contains("type WARNING")));
  }

  @Test
  void pageSevenCanShowWarningOlderThanFiftyMoreRecentRecords() {
    grant("history");
    var warning = record(Type.WARNING, Status.EXPIRED, "MP-OLD-WARNING");
    when(store.history(ownId, 7, 10))
        .thenReturn(
            CompletableFuture.completedFuture(new HistoryPage(List.of(warning), 7, 10, 61)));
    invoke("history", "--page", "7");
    verify(store).history(ownId, 7, 10);
    verify(store, never()).history(any(UUID.class));
    assertTrue(
        output.stream()
            .anyMatch(line -> line.contains("MP-OLD-WARNING") && line.contains("WARNING")));
    assertTrue(output.stream().anyMatch(line -> line.contains("page 7 of 7 (61 records)")));
  }

  @Test
  void nextPageHintUsesPluginNamespaceToAvoidCommandCollisions() {
    grant("warnings");
    when(store.warnings(ownId, 1, 10))
        .thenReturn(
            CompletableFuture.completedFuture(
                new HistoryPage(List.of(record(Type.WARNING, Status.ACTIVE, "MP-A")), 1, 10, 11)));
    invoke("warnings");
    assertTrue(
        output.stream()
            .anyMatch(
                line -> line.contains("/mineplaypunishments:warnings " + ownId + " --page 2")));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"warnings", "warns", "mineplaypunishments:warnings", "mineplaypunishments:warns"})
  void aliasesAndNamespacedCommandsUseTheWarningLookup(String name) {
    grant("warnings");
    when(store.warnings(ownId, 1, 10))
        .thenReturn(CompletableFuture.completedFuture(new HistoryPage(List.of(), 1, 10, 0)));
    invoke(name);
    verify(store).warnings(ownId, 1, 10);
    assertTrue(output.stream().anyMatch(line -> line.contains("No warning records")));
  }

  @Test
  void existingHistoryPermissionsRemainCompatibleForOtherPlayerWarnings() {
    grant("history", "history.others");
    when(store.warnings(otherId, 1, 10))
        .thenReturn(CompletableFuture.completedFuture(new HistoryPage(List.of(), 1, 10, 0)));
    invoke("warnings", otherId.toString());
    verify(store).warnings(otherId, 1, 10);
  }

  @Test
  void ownWarningPermissionDoesNotPermitOtherPlayersOrAllTypeHistory() {
    grant("warnings");
    invoke("warnings", otherId.toString());
    invoke("history");
    verify(store, never()).warnings(any(), anyInt(), anyInt());
    verify(store, never()).history(any(), anyInt(), anyInt());
  }

  @Test
  void warningOthersPermissionStillRequiresBasePermission() {
    grant("warnings.others");
    invoke("warnings", otherId.toString());
    verify(store, never()).resolve(anyString());
    verify(store, never()).warnings(any(), anyInt(), anyInt());
  }

  @Test
  void permissionsAreRecheckedAfterTargetResolutionBeforeReadingRecords() {
    grant("warnings", "warnings.others");
    CompletableFuture<Optional<UUID>> resolved = new CompletableFuture<>();
    when(store.resolve(otherId.toString())).thenReturn(resolved);
    invoke("warnings", otherId.toString());
    permissions.clear();
    resolved.complete(Optional.of(otherId));
    verify(store, never()).warnings(any(), anyInt(), anyInt());
  }

  @Test
  void permissionsAreRecheckedAfterDatabaseCompletionBeforeShowingRecords() {
    grant("warnings", "warnings.others");
    CompletableFuture<HistoryPage> pending = new CompletableFuture<>();
    when(store.warnings(otherId, 1, 10)).thenReturn(pending);
    invoke("warnings", otherId.toString());
    permissions.remove("mineplaypunishments.warnings.others");
    pending.complete(
        new HistoryPage(List.of(record(Type.WARNING, Status.ACTIVE, "MP-PRIVATE")), 1, 10, 1));
    assertTrue(output.isEmpty());
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "-1", "many", "2147483648"})
  void malformedPageCannotReachRecordLookup(String page) {
    grant("warnings");
    invoke("warnings", "--page", page);
    verify(store, never()).warnings(any(), anyInt(), anyInt());
    assertTrue(output.stream().anyMatch(line -> line.startsWith("error ")));
  }

  @Test
  void outOfRangePageIsNotReportedAsAnEmptyWarningHistory() {
    grant("warnings");
    when(store.warnings(ownId, 99, 10))
        .thenReturn(CompletableFuture.completedFuture(new HistoryPage(List.of(), 99, 10, 12)));
    invoke("warnings", "--page", "99");
    assertTrue(
        output.stream()
            .anyMatch(line -> line.contains("out of range") && line.contains("2 pages")));
    assertFalse(output.stream().anyMatch(line -> line.contains("No warning records")));
  }

  @Test
  void failedQueryIsReportedWithoutPartialHistory() {
    grant("warnings");
    when(store.warnings(ownId, 1, 10))
        .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("database")));
    invoke("warnings");
    assertEquals(List.of("error message Warnings could not be loaded"), output);
  }

  @Test
  void consoleMustSupplyTarget() {
    ConsoleCommandSender console = mock(ConsoleCommandSender.class);
    when(console.hasPermission(anyString())).thenReturn(true);
    invoke(console, "warnings");
    verify(store, never()).warnings(any(), anyInt(), anyInt());
    assertTrue(output.stream().anyMatch(line -> line.contains("Console must specify")));
  }

  @Test
  void registrationAndMetadataExposeWarningsAliasAndOpPermissions() {
    Map<String, PluginCommand> registered = new HashMap<>();
    when(plugin.getCommand(anyString()))
        .thenAnswer(
            call ->
                registered.computeIfAbsent(
                    call.getArgument(0), ignored -> mock(PluginCommand.class)));
    commands.register();
    verify(registered.get("warnings")).setExecutor(commands);
    verify(registered.get("warnings")).setTabCompleter(commands);
    var yaml =
        YamlConfiguration.loadConfiguration(
            new InputStreamReader(
                getClass().getResourceAsStream("/plugin.yml"), StandardCharsets.UTF_8));
    assertEquals(List.of("warns"), yaml.getStringList("commands.warnings.aliases"));
    assertEquals("op", yaml.getString("permissions.mineplaypunishments.warnings.default"));
    assertEquals("op", yaml.getString("permissions.mineplaypunishments.warnings.others.default"));
    assertTrue(yaml.getString("commands.history.usage").contains("--page"));
  }
}
