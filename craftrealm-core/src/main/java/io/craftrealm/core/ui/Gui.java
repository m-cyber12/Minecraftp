package io.craftrealm.core.ui;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A chest-style menu.
 *
 * <p>Subclasses implement {@link #render()} and register actions per slot. The
 * listener cancels every click in a CraftRealm inventory, so players can never
 * drag items out of a menu — the number one cause of GUI item duplication.</p>
 */
public abstract class Gui implements InventoryHolder {

    protected final Player viewer;
    protected final GuiTheme theme;
    private final Inventory inventory;
    private final int rows;
    private final Map<Integer, Consumer<GuiClick>> actions = new HashMap<>();
    private Runnable closeHandler = () -> {
    };

    protected Gui(Player viewer, GuiTheme theme, int rows, Component title) {
        this.viewer = viewer;
        this.theme = theme;
        this.rows = Math.max(1, Math.min(6, rows));
        this.inventory = Bukkit.createInventory(this, this.rows * 9, title);
    }

    /** Builds the menu contents. Called on open and on every {@link #refresh()}. */
    public abstract void render();

    // ------------------------------------------------------------------ actions

    /** Draws an icon and binds its click handler. */
    protected void set(int slot, ItemStack item, Consumer<GuiClick> action) {
        if (slot < 0 || slot >= inventory.getSize()) {
            return;
        }
        inventory.setItem(slot, item);
        if (action == null) {
            actions.remove(slot);
        } else {
            actions.put(slot, action);
        }
    }

    protected void set(int slot, ItemStack item) {
        set(slot, item, null);
    }

    protected void set(int slot, ItemBuilder builder, Consumer<GuiClick> action) {
        set(slot, builder.build(), action);
    }

    /** Fills the last row with a frame colour except for the given slots. */
    protected void frame(int... keep) {
        for (int slot = inventory.getSize() - 9; slot < inventory.getSize(); slot++) {
            boolean skip = false;
            for (int keepSlot : keep) {
                if (keepSlot == slot) {
                    skip = true;
                    break;
                }
            }
            if (!skip) {
                set(slot, theme.filler());
            }
        }
    }

    /** Fills every empty slot with a neutral pane. */
    protected void fillEmpty() {
        ItemStack filler = theme.filler();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (inventory.getItem(slot) == null) {
                inventory.setItem(slot, filler);
            }
        }
    }

    /** Standard back button. */
    protected void backButton(Runnable onBack) {
        set(inventory.getSize() - 9, ItemBuilder.of(GuiTheme.Icon.BACK.material())
                .name(theme.label("gui.back", theme.tag("oak") + "Back"))
                .build(), click -> {
            theme.play(viewer, GuiTheme.Click.PAGE);
            onBack.run();
        });
    }

    /** Standard close button. */
    protected void closeButton() {
        set(inventory.getSize() - 1, ItemBuilder.of(Material.BARRIER)
                .name(theme.label("gui.close", theme.tag("redstone") + "Close"))
                .build(), click -> {
            theme.play(viewer, GuiTheme.Click.CLOSE);
            viewer.closeInventory();
        });
    }

    // -------------------------------------------------------------------- hooks

    /**
     * Whether players may move items inside this menu.
     *
     * <p>Off by default — that is what stops the classic "drag an item out of a
     * shop GUI" duplication. The sell menu opts in and returns whatever is left on
     * close.</p>
     */
    public boolean allowsItemMovement() {
        return false;
    }

    public void handleClick(GuiClick click) {
        Consumer<GuiClick> action = actions.get(click.slot());
        if (action != null) {
            try {
                action.accept(click);
            } catch (RuntimeException exception) {
                io.craftrealm.core.CraftRealmCore.logger()
                        .warning("GUI action failed in " + getClass().getSimpleName() + ": " + exception);
                theme.play(viewer, GuiTheme.Click.DENY);
            }
        }
    }

    public void onClose() {
        closeHandler.run();
    }

    protected void onClose(Runnable handler) {
        this.closeHandler = handler == null ? () -> {
        } : handler;
    }

    // ------------------------------------------------------------------- state

    public void open() {
        render();
        viewer.openInventory(inventory);
        theme.play(viewer, GuiTheme.Click.OPEN);
    }

    /** Re-renders while the menu is open (after an async result arrives). */
    public void refresh() {
        if (viewer.getOpenInventory().getTopInventory() != inventory) {
            return;
        }
        render();
    }

    public int size() {
        return inventory.getSize();
    }

    public int rows() {
        return rows;
    }

    public Player viewer() {
        return viewer;
    }

    public GuiTheme theme() {
        return theme;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
