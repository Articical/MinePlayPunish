package cloud.mineplay.punishments.communityservice;

import com.google.gson.Gson;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Captured and applied exclusively on the server thread. No Bukkit objects cross the database
 * boundary.
 */
record PlayerState(
    int version,
    String[] inventory,
    String[] armour,
    String offhand,
    String cursor,
    int heldSlot,
    int level,
    int totalExperience,
    float experience,
    double health,
    int food,
    float saturation,
    float exhaustion,
    List<Effect> effects,
    String gameMode,
    boolean allowFlight,
    boolean flying,
    String world,
    double x,
    double y,
    double z,
    float yaw,
    float pitch) {
  private static final Gson JSON = new Gson();

  record Effect(
      String key, int duration, int amplifier, boolean ambient, boolean particles, boolean icon) {}

  static PlayerState capture(Player p) {
    Location l = p.getLocation();
    return new PlayerState(
        1,
        encode(p.getInventory().getStorageContents()),
        encode(p.getInventory().getArmorContents()),
        encode(p.getInventory().getItemInOffHand()),
        encode(p.getItemOnCursor()),
        p.getInventory().getHeldItemSlot(),
        p.getLevel(),
        p.getTotalExperience(),
        p.getExp(),
        p.getHealth(),
        p.getFoodLevel(),
        p.getSaturation(),
        p.getExhaustion(),
        p.getActivePotionEffects().stream()
            .map(
                e ->
                    new Effect(
                        e.getType().getKey().toString(),
                        e.getDuration(),
                        e.getAmplifier(),
                        e.isAmbient(),
                        e.hasParticles(),
                        e.hasIcon()))
            .toList(),
        p.getGameMode().name(),
        p.getAllowFlight(),
        p.isFlying(),
        l.getWorld().getUID().toString(),
        l.getX(),
        l.getY(),
        l.getZ(),
        l.getYaw(),
        l.getPitch());
  }

  String json() {
    return JSON.toJson(this);
  }

  static PlayerState parse(String data) {
    PlayerState state = JSON.fromJson(data, PlayerState.class);
    if (state == null
        || state.version != 1
        || state.inventory == null
        || state.inventory.length != 36
        || state.armour == null
        || state.armour.length != 4
        || state.effects == null)
      throw new IllegalArgumentException("Unsupported or incomplete player snapshot");
    return state;
  }

  void apply(Player p) {
    p.closeInventory();
    p.getInventory().clear();
    p.getInventory().setStorageContents(decode(inventory));
    p.getInventory().setArmorContents(decode(armour));
    p.getInventory().setItemInOffHand(decode(offhand));
    p.setItemOnCursor(decode(cursor));
    p.getInventory().setHeldItemSlot(heldSlot);
    p.getActivePotionEffects().forEach(e -> p.removePotionEffect(e.getType()));
    for (Effect e : effects) {
      PotionEffectType type = Registry.POTION_EFFECT_TYPE.get(NamespacedKey.fromString(e.key));
      if (type == null) throw new IllegalStateException("Unknown saved potion effect");
      p.addPotionEffect(
          new PotionEffect(type, e.duration, e.amplifier, e.ambient, e.particles, e.icon));
    }
    p.setGameMode(GameMode.valueOf(gameMode));
    p.setAllowFlight(allowFlight);
    p.setFlying(flying && allowFlight);
    p.setLevel(level);
    p.setTotalExperience(totalExperience);
    p.setExp(experience);
    p.setHealth(health);
    p.setFoodLevel(food);
    p.setSaturation(saturation);
    p.setExhaustion(exhaustion);
    p.setFireTicks(0);
    p.setFallDistance(0);
  }

  boolean matches(Player p) {
    boolean equipment =
        equalItems(decode(inventory), p.getInventory().getStorageContents())
            && equalItems(decode(armour), p.getInventory().getArmorContents())
            && equalItem(decode(offhand), p.getInventory().getItemInOffHand())
            && equalItem(decode(cursor), p.getItemOnCursor())
            && p.getInventory().getHeldItemSlot() == heldSlot;
    boolean effectMatch =
        p.getActivePotionEffects().size() == effects.size()
            && effects.stream()
                .allMatch(
                    e -> {
                      PotionEffectType type =
                          Registry.POTION_EFFECT_TYPE.get(NamespacedKey.fromString(e.key));
                      PotionEffect actual = type == null ? null : p.getPotionEffect(type);
                      return actual != null
                          && actual.getAmplifier() == e.amplifier
                          && actual.getDuration() == e.duration
                          && actual.isAmbient() == e.ambient
                          && actual.hasParticles() == e.particles
                          && actual.hasIcon() == e.icon;
                    });
    return equipment
        && effectMatch
        && p.getGameMode().name().equals(gameMode)
        && p.getAllowFlight() == allowFlight
        && p.isFlying() == (flying && allowFlight)
        && p.getLevel() == level
        && p.getTotalExperience() == totalExperience
        && p.getExp() == experience
        && Math.abs(p.getHealth() - health) < 0.001
        && p.getFoodLevel() == food
        && p.getSaturation() == saturation
        && p.getExhaustion() == exhaustion;
  }

  private static boolean equalItem(ItemStack a, ItemStack b) {
    if (a == null || a.getType().isAir()) return b == null || b.getType().isAir();
    return a.equals(b);
  }

  private static boolean equalItems(ItemStack[] saved, ItemStack[] actual) {
    if (actual == null || saved.length != actual.length) return false;
    // Bukkit implementations may expose an empty slot as either null or an AIR item.
    // Normalise emptiness without weakening equality for any saved nonempty stack.
    for (int i = 0; i < saved.length; i++) if (!equalItem(saved[i], actual[i])) return false;
    return true;
  }

  private static String[] encode(ItemStack[] items) {
    return Arrays.stream(items).map(PlayerState::encode).toArray(String[]::new);
  }

  private static String encode(ItemStack item) {
    return item == null || item.getType().isAir()
        ? null
        : Base64.getEncoder().encodeToString(item.serializeAsBytes());
  }

  private static ItemStack[] decode(String[] items) {
    return Arrays.stream(items).map(PlayerState::decode).toArray(ItemStack[]::new);
  }

  private static ItemStack decode(String item) {
    return item == null ? null : ItemStack.deserializeBytes(Base64.getDecoder().decode(item));
  }
}
