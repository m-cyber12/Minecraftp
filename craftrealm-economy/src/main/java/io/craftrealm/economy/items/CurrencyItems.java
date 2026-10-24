package io.craftrealm.economy.items;

import io.craftrealm.core.config.Settings;
import io.craftrealm.core.text.Messages;
import io.craftrealm.core.ui.ItemBuilder;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The two physical forms of Crowns.
 *
 * <ul>
 *   <li><b>Crown Coin</b> — a gold nugget you right-click to bank. Each coin
 *       carries a unique id; redeeming it writes a ledger row keyed
 *       {@code coin:<id>}, so a duplicated coin pays out exactly once.</li>
 *   <li><b>Banknote</b> — a signed paper note. The id is stored in the item's
 *       persistent data together with an HMAC, and the database records the
 *       redemption. Notes cannot be redeemed twice, even across servers.</li>
 * </ul>
 *
 * <p>All state lives in the item's {@link PersistentDataContainer}; nothing here
 * touches the database.</p>
 */
public final class CurrencyItems {

    private final Plugin plugin;
    private final Messages messages;
    private final NoteSecret secret;
    /** Custom model data for the coin icon, or {@code null} when the pack is off. */
    private final Integer coinModelData;
    /** Custom model data for the note icon, or {@code null} when the pack is off. */
    private final Integer noteModelData;
    private final NamespacedKey coinId;
    private final NamespacedKey coinValue;
    private final NamespacedKey noteId;
    private final NamespacedKey noteAmount;
    private final NamespacedKey noteSignature;

    public CurrencyItems(Plugin plugin, Messages messages, NoteSecret secret,
                         Settings.ThemeSettings theme) {
        this.plugin = plugin;
        // The resource pack paints tokens as a struck coin and a signed note.
        // Without the pack these numbers are inert, so both paths look correct.
        this.coinModelData = theme != null && theme.customModelData()
                ? theme.customModelDataOffset() + 500 : null;
        this.noteModelData = theme != null && theme.customModelData()
                ? theme.customModelDataOffset() + 501 : null;
        this.messages = messages;
        this.secret = secret;
        this.coinId = new NamespacedKey(plugin, "coin_id");
        this.coinValue = new NamespacedKey(plugin, "coin_value");
        this.noteId = new NamespacedKey(plugin, "note_id");
        this.noteAmount = new NamespacedKey(plugin, "note_amount");
        this.noteSignature = new NamespacedKey(plugin, "note_signature");
    }

    // -------------------------------------------------------------------- coins

    /** Mints a coin worth {@code value} Crowns. */
    public ItemStack coin(double value, String formattedValue) {
        ItemStack item = ItemBuilder.of(Material.GOLD_NUGGET)
                .name(messages.raw("item.coin.name"))
                .lore(messages.bundle().lines("item.coin.lore", "amount", formattedValue))
                .customModelData(coinModelData)
                .glint(true)
                .build();
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            PersistentDataContainer data = meta.getPersistentDataContainer();
            data.set(coinId, PersistentDataType.STRING, UUID.randomUUID().toString());
            data.set(coinValue, PersistentDataType.DOUBLE, value);
            item.setItemMeta(meta);
        }
        return item;
    }

    public boolean isCoin(ItemStack item) {
        return data(item).map(data -> data.has(coinId, PersistentDataType.STRING)).orElse(false);
    }

    public String coinId(ItemStack item) {
        return data(item).map(data -> data.get(coinId, PersistentDataType.STRING)).orElse(null);
    }

    public double coinValue(ItemStack item) {
        return data(item).map(data -> data.getOrDefault(coinValue, PersistentDataType.DOUBLE, 0.0)).orElse(0.0);
    }

    // -------------------------------------------------------------------- notes

    /** Mints a signed banknote. */
    public ItemStack note(String id, double amount, String formattedAmount, String issuerName) {
        ItemStack item = ItemBuilder.of(Material.PAPER)
                .name(messages.raw("item.note.name"))
                .lore(messages.bundle().lines("item.note.lore",
                        "amount", formattedAmount,
                        "issuer", issuerName,
                        "id", id))
                .customModelData(noteModelData)
                .glint(true)
                .build();
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            PersistentDataContainer data = meta.getPersistentDataContainer();
            data.set(noteId, PersistentDataType.STRING, id);
            data.set(noteAmount, PersistentDataType.DOUBLE, amount);
            data.set(noteSignature, PersistentDataType.STRING, secret.sign(id, amount));
            item.setItemMeta(meta);
        }
        return item;
    }

    public boolean isNote(ItemStack item) {
        return noteIdOf(item).isPresent();
    }

    public Optional<String> noteIdOf(ItemStack item) {
        return data(item).map(data -> data.get(noteId, PersistentDataType.STRING)).filter(id -> id != null);
    }

    public double noteAmount(ItemStack item) {
        return data(item).map(data -> data.getOrDefault(noteAmount, PersistentDataType.DOUBLE, 0.0)).orElse(0.0);
    }

    /** Rejects notes whose amount or id was tampered with. */
    public boolean signatureValid(ItemStack item) {
        Optional<String> id = noteIdOf(item);
        if (id.isEmpty()) {
            return false;
        }
        return data(item).map(data -> secret.verify(id.get(), noteAmount(item),
                data.get(noteSignature, PersistentDataType.STRING))).orElse(false);
    }

    /** New unique note id (UUID v4, hex, 32 chars). */
    public static String newNoteId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private Optional<PersistentDataContainer> data(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return Optional.empty();
        }
        ItemMeta meta = item.getItemMeta();
        return meta == null ? Optional.empty() : Optional.of(meta.getPersistentDataContainer());
    }

    /** Exposed for the listener so it can rebuild a note after a failed redemption. */
    public List<String> describe(ItemStack item) {
        return List.of("coin=" + isCoin(item), "note=" + isNote(item));
    }

    public Plugin plugin() {
        return plugin;
    }
}
