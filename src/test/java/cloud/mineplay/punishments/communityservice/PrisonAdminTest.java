package cloud.mineplay.punishments.communityservice;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cloud.mineplay.punishments.punishment.PunishmentService;
import cloud.mineplay.punishments.storage.Database;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

class PrisonAdminTest {
  @TempDir Path folder;
  private JavaPlugin plugin;
  private YamlConfiguration config;
  private CommunityServiceManager manager;
  private ConsoleCommandSender console;
  private Player player;
  private World world;
  private MockedStatic<Bukkit> bukkit;

  @BeforeEach
  void setup() throws Exception {
    plugin = mock(JavaPlugin.class);
    when(plugin.namespace()).thenReturn("mineplaypunishments");
    when(plugin.getDataFolder()).thenReturn(folder.toFile());
    try (var stream = getClass().getResourceAsStream("/messages.yml")) {
      Files.copy(stream, folder.resolve("messages.yml"));
    }
    config = new YamlConfiguration();
    config.set("community-service.enabled", true);
    when(plugin.getConfig()).thenReturn(config);
    PunishmentService service = mock(PunishmentService.class);
    when(service.sentence(any(UUID.class))).thenReturn(Optional.empty());
    manager = new CommunityServiceManager(plugin, mock(Database.class), service);
    console = mock(ConsoleCommandSender.class);
    when(console.hasPermission("mineplaypunishments.admin.quarry")).thenReturn(true);
    player = mock(Player.class);
    when(player.hasPermission("mineplaypunishments.admin.quarry")).thenReturn(true);
    when(player.getUniqueId()).thenReturn(UUID.randomUUID());
    world = mock(World.class);
    when(world.getUID()).thenReturn(UUID.randomUUID());
    when(world.getName()).thenReturn("world");
    when(world.getMinHeight()).thenReturn(-64);
    when(world.getMaxHeight()).thenReturn(320);
    bukkit = mockStatic(Bukkit.class);
    bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
  }

  @AfterEach
  void close() {
    bukkit.close();
  }

  private void corner(String path, int x, int y, int z) {
    config.set("community-service." + path + ".x", x);
    config.set("community-service." + path + ".y", y);
    config.set("community-service." + path + ".z", z);
  }

  private void largeQuarry() {
    config.set("community-service.quarry.world", "world");
    corner("quarry.pos1", -100, 40, -100);
    corner("quarry.pos2", 100, 100, 100);
    config.set("community-service.quarry.maximum-volume", 50000);
    config.set("community-service.quarry.arrival.world", "world");
    config.set("community-service.quarry.arrival.x", 10.5);
  }

  @Test
  void copyQuarryPreservesLargeLegacySelectionWithoutValidatingOrChangingTheMine() {
    largeQuarry();
    Map<String, Object> original =
        Map.copyOf(config.getConfigurationSection("community-service.quarry").getValues(true));
    manager.adminPrison(console, "CoPyQuArRy");
    assertEquals("world", config.getString("community-service.prison.world"));
    assertEquals(-100, config.getInt("community-service.prison.pos1.x"));
    assertEquals(40, config.getInt("community-service.prison.pos1.y"));
    assertEquals(-100, config.getInt("community-service.prison.pos1.z"));
    assertEquals(100, config.getInt("community-service.prison.pos2.x"));
    assertEquals(100, config.getInt("community-service.prison.pos2.y"));
    assertEquals(100, config.getInt("community-service.prison.pos2.z"));
    assertEquals(
        original, config.getConfigurationSection("community-service.quarry").getValues(true));
    assertFalse(manager.ready());
    verify(plugin).saveConfig();
    verify(world, never()).getBlockAt(anyInt(), anyInt(), anyInt());
    verify(world, never()).isChunkLoaded(anyInt(), anyInt());
    assertTrue(message(console).contains("Saved prison boundary"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"pos1", "pos2", "pos1.x", "pos2.z"})
  void copyQuarryRejectsMissingCornerDataWithoutSaving(String missing) {
    largeQuarry();
    config.set("community-service.quarry." + missing, null);
    String before = config.saveToString();
    manager.adminPrison(console, "copyquarry");
    assertEquals(before, config.saveToString());
    verify(plugin, never()).saveConfig();
    assertTrue(message(console).contains("Set both quarry corners first"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"copyquarry", "setpos1", "setpos2", "validate"})
  void prisonAdministrationRejectsMissingPermission(String operation) {
    largeQuarry();
    String before = config.saveToString();
    when(console.hasPermission("mineplaypunishments.admin.quarry")).thenReturn(false);
    manager.adminPrison(console, operation);
    assertEquals(before, config.saveToString());
    verify(plugin, never()).saveConfig();
    assertTrue(message(console).contains("do not have permission"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"setpos1", "setpos2"})
  void selectingAPrisonCornerSavesGeometryDisablesAdmissionAndRefreshesProtection(String operation)
      throws Exception {
    config.set("community-service.prison.world", "world");
    boolean first = operation.equals("setpos1");
    corner(
        "prison." + (first ? "pos2" : "pos1"), first ? 25 : 20, first ? 90 : 60, first ? 25 : 20);
    int x = first ? 20 : 25, y = first ? 60 : 90;
    Block selected = block(x, y, x);
    when(player.getTargetBlockExact(6)).thenReturn(selected);
    field("ready", true);
    field(
        "protection",
        List.of(new PrisonProtection(world.getUID(), new QuarryBounds(0, 50, 0, 5, 90, 5))));
    manager.adminPrison(player, operation);
    String target = "community-service.prison." + (first ? "pos1" : "pos2");
    assertEquals("world", config.getString("community-service.prison.world"));
    assertEquals(x, config.getInt(target + ".x"));
    assertEquals(y, config.getInt(target + ".y"));
    assertEquals(x, config.getInt(target + ".z"));
    assertFalse(manager.ready());
    verify(plugin).saveConfig();
    BlockBreakEvent inside = breaking(block(22, 70, 22));
    manager.breakBlock(inside);
    verify(inside).setCancelled(true);
    BlockBreakEvent outside = breaking(block(2, 70, 2));
    manager.breakBlock(outside);
    verify(outside, never()).setCancelled(true);
  }

  @Test
  void selectingACornerRequiresAPlayerAndAReachableBlock() {
    manager.adminPrison(console, "setpos1");
    assertTrue(message(console).contains("must be run by a player"));
    manager.adminPrison(player, "setpos1");
    assertTrue(message(player).contains("within six blocks"));
    verify(plugin, never()).saveConfig();
  }

  @Test
  void validateReportsIncompleteLayoutWithoutSavingOrScanning() throws Exception {
    field("ready", true);
    manager.adminPrison(console, "validate");
    assertFalse(manager.ready());
    assertTrue(message(console).contains("Layout is incomplete"));
    verify(plugin, never()).saveConfig();
    verify(world, never()).getBlockAt(anyInt(), anyInt(), anyInt());
    verify(world, never()).isChunkLoaded(anyInt(), anyInt());
  }

  private Block block(int x, int y, int z) {
    Block result = mock(Block.class);
    when(result.getLocation()).thenReturn(new Location(world, x, y, z));
    return result;
  }

  private BlockBreakEvent breaking(Block block) {
    BlockBreakEvent event = mock(BlockBreakEvent.class);
    when(event.getPlayer()).thenReturn(player);
    when(event.getBlock()).thenReturn(block);
    return event;
  }

  private static String message(CommandSender sender) {
    var capture = ArgumentCaptor.forClass(Component.class);
    verify(sender).sendMessage(capture.capture());
    return PlainTextComponentSerializer.plainText().serialize(capture.getValue());
  }

  private void field(String name, Object value) throws Exception {
    var field = CommunityServiceManager.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(manager, value);
  }
}
