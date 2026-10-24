package io.craftrealm.core.ui;

import io.craftrealm.core.config.Settings;
import io.craftrealm.core.text.MessageBundle;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;

/**
 * The "craftsman's workshop" look, in code.
 *
 * <p>Colours come from the game's own materials: oak and spruce for frames,
 * deepslate for panels, redstone for destructive actions, gold and copper for
 * currency, emerald for gains and lava orange for warnings. No purple gradients,
 * no neon.</p>
 *
 * <p>Sound design matters as much as colour here: note blocks for buttons, a
 * villager "hmm" for trades, an anvil for refusals.</p>
 */
public final class GuiTheme {

    /** Semantic icons, so a category means the same thing in every menu. */
    public enum Icon {
        BANK("GOLD_INGOT"),
        JOBS("VILLAGER_SPAWN_EGG"),
        PUNISHMENTS("TNT"),
        EVENTS("CLOCK"),
        SETTINGS("COMPARATOR"),
        PLAYERS("PLAYER_HEAD"),
        ECONOMY("DIAMOND"),
        HEALTH("REDSTONE_LAMP"),
        LOGS("WRITABLE_BOOK"),
        WORLD("GRASS_BLOCK"),
        SHOP("EMERALD"),
        AUCTION("GOLDEN_HORSE_ARMOR"),
        CLAIMS("OAK_FENCE"),
        QUESTS("PAPER"),
        COSMETICS("AMETHYST_SHARD"),
        SEASONS("SUNFLOWER"),
        BOUNTY("IRON_SWORD"),
        BACK("OAK_DOOR"),
        CLOSE("BARRIER"),
        NEXT("SPECTRAL_ARROW"),
        PREVIOUS("ARROW"),
        CONFIRM("LIME_DYE"),
        CANCEL("RED_DYE"),
        MAILBOX("CHEST"),
        TREASURE("COMPASS");

        private final String material;

        Icon(String material) {
            this.material = material;
        }

        public Material material() {
            Material resolved = Material.matchMaterial(material);
            return resolved == null ? Material.STONE : resolved;
        }
    }

    /** Semantic sounds. */
    public enum Click {
        BUTTON(Sound.UI_BUTTON_CLICK, 0.6f, 1.2f),
        OPEN(Sound.BLOCK_CHEST_OPEN, 0.5f, 1.1f),
        CLOSE(Sound.BLOCK_CHEST_CLOSE, 0.5f, 1.0f),
        PAGE(Sound.ITEM_BOOK_PAGE_TURN, 0.7f, 1.3f),
        SUCCESS(Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.4f),
        PURCHASE(Sound.ENTITY_VILLAGER_TRADE, 0.8f, 1.0f),
        DENY(Sound.BLOCK_ANVIL_LAND, 0.4f, 0.8f),
        CONFIRM(Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 1.6f),
        VILLAGER(Sound.ENTITY_VILLAGER_AMBIENT, 0.6f, 1.0f),
        LEVEL_UP(Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.2f),
        CLAIM(Sound.ENTITY_ENDERMAN_TELEPORT, 0.4f, 1.4f),
        WARNING(Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.7f);

        private final Sound sound;
        private final float volume;
        private final float pitch;

        Click(Sound sound, float volume, float pitch) {
            this.sound = sound;
            this.volume = volume;
            this.pitch = pitch;
        }
    }

    private final Settings.ThemeSettings settings;
    private final Map<String, String> palette;

    public GuiTheme(Settings.ThemeSettings settings) {
        this.settings = settings;
        this.palette = settings.palette();
    }

    // ------------------------------------------------------------------ colours

    /** Hex colour for a palette token, e.g. {@code gold} → {@code #e8b339}. */
    public String colour(String token) {
        return palette.getOrDefault(token, "#ffffff");
    }

    /** MiniMessage tag for a token, e.g. {@code <#e8b339>}. */
    public String tag(String token) {
        return "<" + colour(token) + ">";
    }

    public Component text(String miniMessage) {
        return MessageBundle.parse(miniMessage);
    }

    // ------------------------------------------------------------------- icons

    public ItemStack icon(Icon icon, String name, List<String> lore) {
        ItemBuilder builder = ItemBuilder.of(icon.material()).name(name);
        if (lore != null && !lore.isEmpty()) {
            builder.lore(lore);
        }
        if (settings.customModelData()) {
            builder.customModelData(settings.customModelDataOffset() + icon.ordinal());
        }
        return builder.build();
    }

    /** Frame block used to border menus. */
    public Material frameMaterial() {
        return Material.SPRUCE_PLANKS;
    }

    /** Neutral filler for empty slots. */
    public Material fillerMaterial() {
        return Material.GRAY_STAINED_GLASS_PANE;
    }

    public ItemStack filler() {
        return ItemBuilder.of(fillerMaterial()).name("<dark_gray>").build();
    }

    // ------------------------------------------------------------------- sound

    public void play(Player player, Click click) {
        if (player == null || !settings.sounds()) {
            return;
        }
        player.playSound(player.getLocation(), click.sound, click.volume, click.pitch);
    }

    public boolean soundsEnabled() {
        return settings.sounds();
    }

    /** Integer to put in custom model data, or {@code null} when disabled. */
    public Integer modelData(int index) {
        return settings.customModelData() ? settings.customModelDataOffset() + index : null;
    }
}
