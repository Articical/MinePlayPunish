package cloud.mineplay.punishments.commands;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cloud.mineplay.punishments.MineplayPunishments;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

class ModerationTabCompletionTest {
  private MineplayPunishments plugin;
  private ModerationCommands commands;
  private Player sender;
  private Player alice;
  private Player bob;
  private Player hidden;
  private MockedStatic<Bukkit> bukkit;
  private final Set<String> permissions = new HashSet<>();

  @BeforeEach
  void setup() {
    plugin = mock(MineplayPunishments.class);
    commands = new ModerationCommands(plugin);
    sender = player("Staff");
    alice = player("Alice");
    bob = player("Bob");
    hidden = player("Hidden");
    when(sender.hasPermission(anyString()))
        .thenAnswer(call -> permissions.contains(call.getArgument(0)));
    when(sender.canSee(any(Player.class))).thenReturn(true);
    when(sender.canSee(hidden)).thenReturn(false);
    bukkit = mockStatic(Bukkit.class);
    bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(bob, hidden, sender, alice));
  }

  @AfterEach
  void cleanup() {
    bukkit.close();
  }

  private static Player player(String name) {
    Player player = mock(Player.class);
    when(player.getName()).thenReturn(name);
    return player;
  }

  private void grant(String... suffixes) {
    for (String suffix : suffixes) permissions.add("mineplaypunishments." + suffix);
  }

  private List<String> complete(String command, String... args) {
    return complete(sender, command, command, args);
  }

  private List<String> complete(CommandSender actor, String name, String alias, String... args) {
    Command command = mock(Command.class);
    when(command.getName()).thenReturn(name);
    return commands.onTabComplete(actor, command, alias, args);
  }

  @Test
  void quarrySetupCompletesAtEveryCommandPosition() {
    grant("admin", "admin.quarry");
    assertEquals(List.of("acknowledge", "admin"), complete("mp", ""));
    assertEquals(List.of("admin"), complete("mp", "ad"));
    assertEquals(List.of("status", "quarry", "prison"), complete("mp", "admin", ""));
    assertEquals(List.of("quarry"), complete("mp", "admin", "q"));
    assertEquals(
        List.of("setpos1", "setpos2", "setspawn", "setrelease", "setfallback", "validate"),
        complete("mp", "admin", "quarry", ""));
    assertEquals(List.of("setpos1", "setpos2"), complete("mp", "admin", "quarry", "setp"));
    assertTrue(complete("mp", "admin", "quarry", "setpos1", "").isEmpty());
    verifyNoInteractions(plugin);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"setpos1", "setpos2", "setspawn", "setrelease", "setfallback", "validate"})
  void eachExactQuarryOperationRemainsCompletable(String operation) {
    grant("admin", "admin.quarry");
    assertEquals(List.of(operation), complete("mp", "admin", "quarry", operation));
  }

  @Test
  void prisonCompletesSeparatelyFromQuarryWithItsSharedPermission() {
    grant("admin", "admin.quarry");
    assertEquals(List.of("prison"), complete("mp", "admin", "pR"));
    assertEquals(
        List.of("setpos1", "setpos2", "copyquarry", "validate"),
        complete("mp", "admin", "prison", ""));
    assertEquals(
        List.of("setpos1", "setpos2"),
        complete(sender, "mp", "mineplaypunishments:mp", "AdMiN", "PrIsOn", "SeTp"));
    assertEquals(List.of("copyquarry"), complete("mp", "admin", "prison", "copy"));
    assertTrue(complete("mp", "admin", "prison", "setspawn").isEmpty());
    assertTrue(complete("mp", "admin", "prison", "setpos1", "").isEmpty());
    verifyNoInteractions(plugin);
  }

  @ParameterizedTest
  @ValueSource(strings = {"setpos1", "setpos2", "copyquarry", "validate"})
  void eachPrisonOperationRemainsCompletable(String operation) {
    grant("admin", "admin.quarry");
    assertEquals(List.of(operation), complete("mp", "admin", "prison", operation));
  }

  @Test
  void prisonCompletionRequiresBothExistingPermissions() {
    grant("admin.quarry");
    assertTrue(complete("mp", "admin", "prison", "").isEmpty());
    grant("admin");
    assertEquals(List.of("prison"), complete("mp", "admin", "p"));
    permissions.remove("mineplaypunishments.admin.quarry");
    assertTrue(complete("mp", "admin", "p").isEmpty());
    assertTrue(complete("mp", "admin", "prison", "").isEmpty());
  }

  @Test
  void prefixesAndParentBranchesAreCaseInsensitiveIncludingNamespacedAliases() {
    grant("admin", "admin.quarry");
    assertEquals(
        List.of("setpos1", "setpos2"),
        complete(sender, "MP", "mineplaypunishments:mp", "AdMiN", "QuArRy", "SeTp"));
    assertEquals(
        List.of("validate"),
        complete(
            sender, "mineplaypunishments:MP", "mineplaypunishments:mp", "admin", "quarry", "VaL"));
    assertTrue(complete("mp", "admin", "quarry", "unknown").isEmpty());
    assertTrue(complete("mp", "quarry", "").isEmpty());
  }

  @Test
  void childPermissionDoesNotBypassMissingAdminAndRevocationHidesSuggestions() {
    assertEquals(List.of("acknowledge"), complete("mp", ""));
    grant("admin.quarry");
    assertTrue(complete("mp", "ad").isEmpty());
    assertTrue(complete("mp", "admin", "").isEmpty());
    assertTrue(complete("mp", "admin", "quarry", "").isEmpty());
    grant("admin");
    assertEquals(List.of("quarry"), complete("mp", "admin", "q"));
    permissions.remove("mineplaypunishments.admin.quarry");
    assertEquals(List.of("status"), complete("mp", "admin", ""));
    assertTrue(complete("mp", "admin", "quarry", "").isEmpty());
  }

  @ParameterizedTest
  @ValueSource(strings = {"sync", "reload", "recover", "quarry"})
  void adminOperationsRequireTheirOwnPermission(String operation) {
    grant("admin");
    assertFalse(complete("mp", "admin", "").contains(operation));
    grant("admin." + operation);
    assertTrue(complete("mp", "admin", "").contains(operation));
    permissions.remove("mineplaypunishments.admin");
    assertTrue(complete("mp", "admin", "").isEmpty());
  }

  @Test
  void consoleOnlyReceivesExecutableQuarryOperation() {
    ConsoleCommandSender console = mock(ConsoleCommandSender.class);
    when(console.hasPermission(anyString())).thenReturn(true);
    assertEquals(List.of("validate"), complete(console, "mp", "mp", "admin", "quarry", ""));
    assertTrue(complete(console, "mp", "mp", "admin", "quarry", "set").isEmpty());
    assertFalse(complete(console, "mp", "mp", "").contains("acknowledge"));
    assertEquals(
        List.of("copyquarry", "validate"), complete(console, "mp", "mp", "admin", "prison", ""));
    assertTrue(complete(console, "mp", "mp", "admin", "prison", "set").isEmpty());
  }

  @Test
  void recoveryTargetsAreVisibleOnlinePlayersAndRequireBothPermissions() {
    grant("admin.recover");
    assertTrue(complete("mp", "admin", "recover", "").isEmpty());
    grant("admin");
    assertEquals(List.of("Alice", "Bob", "Staff"), complete("mp", "admin", "recover", ""));
    assertEquals(List.of("Bob"), complete("mp", "admin", "recover", "bO"));
    assertTrue(complete("mp", "admin", "recover", "Hidden").isEmpty());
    assertTrue(complete("mp", "admin", "recover", "Bob", "").isEmpty());
  }

  @Test
  void serviceRootIncludesOnlyPermittedOperationsAndIssueTargets() {
    assertEquals(List.of("progress"), complete("service", ""));
    grant("service.release");
    assertEquals(List.of("release"), complete("service", "re"));
    grant("service.modify", "service.issue");
    assertEquals(
        List.of("progress", "release", "add", "issue", "Alice", "Bob", "Staff"),
        complete("service", ""));
    assertEquals(List.of("add", "Alice"), complete("service", "a"));
    assertEquals(List.of("Alice"), complete("service", "release", "a"));
    assertEquals(List.of("Bob"), complete("service", "ADD", "b"));
    permissions.remove("mineplaypunishments.service.release");
    assertTrue(complete("service", "release", "").isEmpty());
  }

  @Test
  void explicitIssueAliasCompletesTargetsOnlyWithIssuePermission() {
    assertTrue(complete("service", "issue", "").isEmpty());
    grant("service.modify");
    assertTrue(complete("service", "iss").isEmpty());
    grant("service.issue");
    assertEquals(List.of("issue"), complete("service", "IsS"));
    assertEquals(List.of("Alice", "Bob", "Staff"), complete("service", "ISSUE", ""));
    assertEquals(List.of("Alice"), complete("service", "issue", "aL"));
    assertTrue(complete("service", "issue", "Alice", "").isEmpty());
    permissions.remove("mineplaypunishments.service.issue");
    assertTrue(complete("service", "issue", "").isEmpty());
  }

  @Test
  void progressAndHistoryDoNotSuggestOtherPlayersWithoutTheirPermissions() {
    assertEquals(List.of("Staff"), complete("service", "progress", ""));
    assertTrue(complete("service", "progress", "a").isEmpty());
    assertTrue(complete("history", "").isEmpty());
    grant("history");
    assertEquals(List.of("Staff"), complete("history", ""));
    grant("service.progress.others", "history.others");
    assertEquals(List.of("Alice"), complete("service", "progress", "AL"));
    assertEquals(List.of("Alice", "Bob", "Staff"), complete("history", ""));
  }

  @Test
  void ordinaryTargetsAndTemporaryDurationsRespectArgumentPositionsAndPermissions() {
    assertTrue(complete("ban", "").isEmpty());
    grant("ban", "tempban");
    assertEquals(List.of("Alice"), complete("ban", "al"));
    assertTrue(complete("ban", "Alice", "").isEmpty());
    assertEquals(List.of("1h", "1d"), complete("tempban", "Alice", "1"));
    assertEquals(List.of("permanent"), complete("tempban", "Alice", "PeR"));
    assertTrue(complete("tempban", "Alice", "1h", "").isEmpty());
    assertTrue(complete("unrelated", "").isEmpty());
  }

  @Test
  void appealSuggestionsArePermissionGatedAndDoNotInventPunishmentIds() {
    assertTrue(complete("mp", "ap").isEmpty());
    assertTrue(complete("mp", "appeal", "some-id", "").isEmpty());
    grant("appeal");
    assertEquals(List.of("appeal"), complete("mp", "ap"));
    assertTrue(complete("mp", "appeal", "").isEmpty());
    assertEquals(List.of("--rotate"), complete("mp", "appeal", "some-id", "--r"));
    assertTrue(complete("mp", "acknowledge", "").isEmpty());
  }

  @Test
  void emptyArgumentArrayAndUnrelatedInventoryPermissionsAreHandledSafely() {
    assertEquals(List.of("acknowledge"), complete("mp"));
    grant("gui");
    assertTrue(complete("mp", "admin", "quarry", "").isEmpty());
  }
}
