package cloud.mineplay.punishments.gui;

import cloud.mineplay.punishments.commands.StaffModeration.Target;
import cloud.mineplay.punishments.punishment.Models.Type;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/** Real Paper UI, separated from session handling so packet/event boundaries can be tested. */
final class PaperMenuView implements PunishMenu.View {
  @Override
  public Inventory open(
      Player player, UUID sessionId, Target target, String title, List<PunishMenu.Button> buttons) {
    Holder holder = new Holder(player.getUniqueId(), sessionId);
    Inventory inventory =
        Bukkit.createInventory(holder, 27, Component.text(title, NamedTextColor.DARK_RED));
    holder.inventory = inventory;
    ItemStack border = item(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
    for (int i = 0; i < inventory.getSize(); i++) inventory.setItem(i, border);
    inventory.setItem(
        4,
        item(
            Material.NAME_TAG,
            PunishMenu.targetLabel(target),
            List.of(
                target.id().toString(),
                "Nothing is issued until you confirm.",
                "This draft expires after 2 minutes.")));
    for (PunishMenu.Button button : buttons) {
      inventory.setItem(
          button.slot(), item(button.icon(), button.label(), List.of(button.detail())));
    }
    player.openInventory(inventory);
    return inventory;
  }

  @Override
  public void reason(
      Player player,
      Target target,
      Type type,
      DurationPreset preset,
      int maximumReasonLength,
      BiConsumer<Player, String> confirm,
      Consumer<Player> cancel) {
    var options = ClickCallback.Options.builder().uses(1).lifetime(PunishMenu.LIFETIME).build();
    var confirmAction =
        DialogAction.customClick(
            (response, audience) -> {
              if (audience instanceof Player actor)
                confirm.accept(actor, response.getText("reason"));
            },
            options);
    var cancelAction =
        DialogAction.customClick(
            (response, audience) -> {
              if (audience instanceof Player actor) cancel.accept(actor);
            },
            options);
    String period =
        preset == null
            ? type == Type.KICK ? "Immediate kick" : "Warning (configured expiry)"
            : preset.label();
    Dialog dialog =
        Dialog.create(
            factory ->
                factory
                    .empty()
                    .base(
                        DialogBase.builder(
                                Component.text(
                                    "Confirm " + PunishMenu.label(type), NamedTextColor.DARK_RED))
                            .canCloseWithEscape(true)
                            .pause(false)
                            .afterAction(DialogBase.DialogAfterAction.CLOSE)
                            .body(
                                List.of(
                                    DialogBody.plainMessage(
                                        Component.text(
                                            "Player: " + PunishMenu.targetLabel(target))),
                                    DialogBody.plainMessage(
                                        Component.text(
                                            target.id().toString(), NamedTextColor.GRAY)),
                                    DialogBody.plainMessage(
                                        Component.text(
                                            "Action: " + PunishMenu.label(type) + " | " + period)),
                                    DialogBody.plainMessage(
                                        Component.text(
                                            "Enter a reason, then confirm to issue. Cancel or"
                                                + " Escape discards this draft."))))
                            .inputs(
                                List.of(
                                    DialogInput.text("reason", Component.text("Reason (required)"))
                                        .initial("")
                                        .maxLength(maximumReasonLength)
                                        .width(300)
                                        .build()))
                            .build())
                    .type(
                        DialogType.confirmation(
                            ActionButton.builder(
                                    Component.text(
                                        "Confirm " + PunishMenu.label(type), NamedTextColor.RED))
                                .action(confirmAction)
                                .build(),
                            ActionButton.builder(Component.text("Cancel"))
                                .action(cancelAction)
                                .build())));
    player.showDialog(dialog);
  }

  @Override
  public void serviceReason(
      Player player,
      Target target,
      Integer presetBlocks,
      int minimumBlocks,
      int maximumBlocks,
      int maximumReasonLength,
      BiConsumer<Player, PunishMenu.ServiceInput> confirm,
      Consumer<Player> cancel) {
    var options = ClickCallback.Options.builder().uses(1).lifetime(PunishMenu.LIFETIME).build();
    var confirmAction =
        DialogAction.customClick(
            (response, audience) -> {
              if (audience instanceof Player actor)
                confirm.accept(
                    actor,
                    new PunishMenu.ServiceInput(
                        response.getText("blocks"), response.getText("reason")));
            },
            options);
    var cancelAction =
        DialogAction.customClick(
            (response, audience) -> {
              if (audience instanceof Player actor) cancel.accept(actor);
            },
            options);
    List<DialogInput> inputs = new ArrayList<>();
    if (presetBlocks == null) {
      inputs.add(
          DialogInput.text(
                  "blocks",
                  Component.text("Blocks (" + minimumBlocks + " to " + maximumBlocks + ")"))
              .initial("")
              .maxLength(10)
              .width(300)
              .build());
    }
    inputs.add(
        DialogInput.text("reason", Component.text("Reason (required)"))
            .initial("")
            .maxLength(maximumReasonLength)
            .width(300)
            .build());
    Dialog dialog =
        Dialog.create(
            factory ->
                factory
                    .empty()
                    .base(
                        DialogBase.builder(
                                Component.text(
                                    "Confirm Community Service", NamedTextColor.DARK_RED))
                            .canCloseWithEscape(true)
                            .pause(false)
                            .afterAction(DialogBase.DialogAfterAction.CLOSE)
                            .body(
                                List.of(
                                    DialogBody.plainMessage(
                                        Component.text(
                                            "Player: " + PunishMenu.targetLabel(target))),
                                    DialogBody.plainMessage(
                                        Component.text(
                                            target.id().toString(), NamedTextColor.GRAY)),
                                    DialogBody.plainMessage(
                                        Component.text(
                                            presetBlocks == null
                                                ? "Action: Community Service | Enter a whole-number"
                                                    + " block amount below."
                                                : "Action: Community Service | "
                                                    + presetBlocks
                                                    + " blocks")),
                                    DialogBody.plainMessage(
                                        Component.text(
                                            "Enter a reason, then confirm to issue. Cancel or"
                                                + " Escape discards this draft."))))
                            .inputs(inputs)
                            .build())
                    .type(
                        DialogType.confirmation(
                            ActionButton.builder(
                                    Component.text("Confirm sentence", NamedTextColor.RED))
                                .action(confirmAction)
                                .build(),
                            ActionButton.builder(Component.text("Cancel"))
                                .action(cancelAction)
                                .build())));
    player.showDialog(dialog);
  }

  @Override
  public void closeDialog(Player player) {
    player.closeDialog();
  }

  private static ItemStack item(Material material, String name, List<String> lore) {
    ItemStack item = new ItemStack(material);
    item.editMeta(
        meta -> {
          meta.displayName(
              Component.text(name, NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
          meta.lore(
              lore.stream()
                  .map(
                      line ->
                          (Component)
                              Component.text(line, NamedTextColor.GRAY)
                                  .decoration(TextDecoration.ITALIC, false))
                  .toList());
        });
    return item;
  }

  static final class Holder implements InventoryHolder {
    final UUID owner;
    final UUID sessionId;
    private Inventory inventory;

    Holder(UUID owner, UUID sessionId) {
      this.owner = owner;
      this.sessionId = sessionId;
    }

    @Override
    public Inventory getInventory() {
      return Objects.requireNonNull(inventory);
    }
  }
}
