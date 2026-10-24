package io.craftrealm.core.ui;

import io.craftrealm.core.text.MessageBundle;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Small fluent wrapper for building GUI icons.
 *
 * <p>Every icon in CraftRealm is built here so the parchment-tooltip rule
 * ({@code HIDE_ATTRIBUTES} + {@code HIDE_ADDITIONAL_TOOLTIP}) and the optional
 * custom model data are applied consistently, which is what makes the workshop
 * look coherent instead of a pile of vanilla tooltips.</p>
 */
public final class ItemBuilder {

    private final ItemStack stack;
    private final List<Component> lore = new ArrayList<>();
    private Component name;
    private int amount = 1;
    private Integer customModelData;
    private boolean glint;
    private boolean hideAll = true;

    private ItemBuilder(Material material) {
        this.stack = new ItemStack(material == null ? Material.STONE : material);
    }

    public static ItemBuilder of(Material material) {
        return new ItemBuilder(material);
    }

    public static ItemBuilder of(ItemStack template) {
        ItemBuilder builder = new ItemBuilder(template.getType());
        builder.stack.setItemMeta(template.getItemMeta());
        builder.amount = template.getAmount();
        return builder;
    }

    /** MiniMessage name. */
    public ItemBuilder name(String miniMessage) {
        this.name = MessageBundle.parse(miniMessage);
        return this;
    }

    public ItemBuilder name(Component component) {
        this.name = component;
        return this;
    }

    /** One MiniMessage line of lore. */
    public ItemBuilder lore(String miniMessage) {
        this.lore.add(MessageBundle.parse(miniMessage));
        return this;
    }

    public ItemBuilder lore(Component component) {
        this.lore.add(component);
        return this;
    }

    public ItemBuilder lore(String... miniMessages) {
        Arrays.stream(miniMessages).forEach(this::lore);
        return this;
    }

    public ItemBuilder lore(List<Component> components) {
        this.lore.addAll(components);
        return this;
    }

    /** Blank spacer line. */
    public ItemBuilder blank() {
        this.lore.add(Component.empty());
        return this;
    }

    public ItemBuilder amount(int amount) {
        this.amount = Math.max(1, Math.min(64, amount));
        return this;
    }

    public ItemBuilder customModelData(Integer data) {
        this.customModelData = data;
        return this;
    }

    public ItemBuilder glint(boolean glint) {
        this.glint = glint;
        return this;
    }

    /** Keeps vanilla tooltips (for player heads and real items). */
    public ItemBuilder keepTooltips() {
        this.hideAll = false;
        return this;
    }

    public ItemStack build() {
        stack.setAmount(amount);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            if (name != null) {
                meta.displayName(name);
            }
            if (!lore.isEmpty()) {
                meta.lore(lore);
            }
            if (customModelData != null) {
                meta.setCustomModelData(customModelData);
            }
            if (glint) {
                meta.setEnchantmentGlintOverride(Boolean.TRUE);
            }
            if (hideAll) {
                meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
            }
            stack.setItemMeta(meta);
        }
        return stack;
    }

    /** Convenience for a one-off icon. */
    public static ItemStack icon(Material material, String name, String... lore) {
        ItemBuilder builder = of(material).name(name);
        if (lore.length > 0) {
            builder.lore(lore);
        }
        return builder.build();
    }
}
