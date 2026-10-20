package cloud.mineplay.punishments.communityservice;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class PlayerStateRestorationTest {
  private Player player;
  private PlayerInventory inventory;
  private ItemStack[] storage;
  private ItemStack[] armour;

  @BeforeEach
  void setup() {
    player = mock(Player.class);
    inventory = mock(PlayerInventory.class);
    storage = new ItemStack[36];
    armour = new ItemStack[4];
    when(player.getInventory()).thenReturn(inventory);
    when(inventory.getStorageContents()).thenReturn(storage);
    when(inventory.getArmorContents()).thenReturn(armour);
    when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
    when(player.getActivePotionEffects()).thenReturn(List.of());
    when(player.getHealth()).thenReturn(20.0);
    when(player.getFoodLevel()).thenReturn(20);
  }

  private PlayerState state(String[] contents) {
    return new PlayerState(
        1,
        contents,
        new String[4],
        null,
        null,
        0,
        0,
        0,
        0,
        20,
        20,
        0,
        0,
        List.of(),
        "SURVIVAL",
        false,
        false,
        "00000000-0000-0000-0000-000000000001",
        0,
        64,
        0,
        0,
        0);
  }

  private ItemStack item(boolean air) {
    Material type = mock(Material.class);
    when(type.isAir()).thenReturn(air);
    ItemStack item = mock(ItemStack.class);
    when(item.getType()).thenReturn(type);
    return item;
  }

  @Test
  void restoredEmptySlotsMayBeNullOrAirAcrossEveryEquipmentArea() {
    storage[0] = item(true);
    storage[35] = item(true);
    armour[3] = item(true);
    ItemStack emptyOffhand = item(true);
    ItemStack emptyCursor = item(true);
    when(inventory.getItemInOffHand()).thenReturn(emptyOffhand);
    when(player.getItemOnCursor()).thenReturn(emptyCursor);
    assertTrue(state(new String[36]).matches(player));
  }

  @Test
  void unexpectedPrisonItemStillPreventsConfirmedRestoration() {
    storage[0] = item(false);
    assertFalse(state(new String[36]).matches(player));
  }

  @Test
  void nonemptySavedStacksMustStillMatchInTheirOriginalSlots() {
    String[] saved = new String[36];
    saved[7] = "AA==";
    ItemStack original = item(false);
    try (MockedStatic<ItemStack> stacks = mockStatic(ItemStack.class)) {
      stacks.when(() -> ItemStack.deserializeBytes(any(byte[].class))).thenReturn(original);
      storage[7] = original;
      assertTrue(state(saved).matches(player));
      storage[7] = null;
      storage[8] = original;
      assertFalse(state(saved).matches(player));
      storage[7] = item(false);
      storage[8] = null;
      assertFalse(state(saved).matches(player));
    }
  }

  @Test
  void incompleteInventoryOrChangedPlayerStateCannotConfirmRestoration() {
    when(inventory.getStorageContents()).thenReturn(new ItemStack[35]);
    assertFalse(state(new String[36]).matches(player));
    when(inventory.getStorageContents()).thenReturn(storage);
    when(player.getGameMode()).thenReturn(GameMode.ADVENTURE);
    assertFalse(state(new String[36]).matches(player));
  }
}
