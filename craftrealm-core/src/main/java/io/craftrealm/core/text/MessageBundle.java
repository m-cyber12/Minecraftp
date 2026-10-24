package io.craftrealm.core.text;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A localisation file: {@code lang/en_US.yml}, {@code lang/fa_IR.yml}, or any
 * other locale you drop into the folder.
 *
 * <p>Values are MiniMessage. Placeholders are written as {@code {name}} and filled
 * from varargs; placeholder <em>values</em> are escaped so a player called
 * {@code <red>Admin} cannot inject formatting into staff broadcasts.</p>
 *
 * <p>Pure: it only needs a YAML file, so it can be unit tested without a server.</p>
 */
public final class MessageBundle {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final Map<String, String> messages;
    private final String locale;

    private MessageBundle(Map<String, String> messages, String locale) {
        this.messages = messages;
        this.locale = locale;
    }

    /** Loads a YAML file. Missing files produce an empty (falling-back) bundle. */
    public static MessageBundle load(File file, String locale) {
        Map<String, String> values = new LinkedHashMap<>();
        if (file != null && file.isFile()) {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            for (String key : yaml.getKeys(true)) {
                Object raw = yaml.get(key);
                if (raw instanceof String text) {
                    values.put(key, text);
                } else if (raw instanceof List<?> list) {
                    List<String> lines = new ArrayList<>(list.size());
                    for (Object line : list) {
                        lines.add(String.valueOf(line));
                    }
                    values.put(key, String.join("\n", lines));
                }
            }
        }
        return new MessageBundle(values, locale);
    }

    public static MessageBundle of(Map<String, String> messages, String locale) {
        return new MessageBundle(new LinkedHashMap<>(messages), locale);
    }

    public static MessageBundle empty(String locale) {
        return new MessageBundle(Map.of(), locale);
    }

    /** Layered lookup: the primary locale wins, the fallback bundle fills the gaps. */
    public MessageBundle mergedWith(MessageBundle fallback) {
        Map<String, String> merged = new LinkedHashMap<>(fallback == null ? Map.of() : fallback.messages);
        merged.putAll(messages);
        return new MessageBundle(merged, locale);
    }

    public String locale() {
        return locale;
    }

    public boolean has(String key) {
        return messages.containsKey(key);
    }

    /** Raw MiniMessage string with placeholders applied, before parsing. */
    public String raw(String key, Object... placeholders) {
        String template = messages.get(key);
        if (template == null) {
            return "<red>missing message: " + key;
        }
        return apply(template, placeholders);
    }

    /** Parsed component. Unknown keys render a visible "missing message" marker. */
    public Component component(String key, Object... placeholders) {
        return MINI.deserialize(raw(key, placeholders));
    }

    /** Parses a literal MiniMessage string (for config-defined text). */
    public static Component parse(String miniMessage) {
        return MINI.deserialize(miniMessage == null ? "" : miniMessage);
    }

    /** Parses and strips formatting — used for console output and the web API. */
    public static String plain(Component component) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(component);
    }

    public String plain(String key, Object... placeholders) {
        return plain(component(key, placeholders));
    }

    public List<Component> lines(String key, Object... placeholders) {
        String raw = raw(key, placeholders);
        List<Component> lines = new ArrayList<>();
        for (String line : raw.split("\n")) {
            lines.add(MINI.deserialize(line));
        }
        return lines;
    }

    /**
     * Substitutes {@code {key}} tokens. Values are escaped so untrusted input
     * (names, reasons, amounts from URLs) can never introduce MiniMessage tags.
     */
    private static String apply(String template, Object... placeholders) {
        if (placeholders == null || placeholders.length == 0) {
            return template;
        }
        String result = template;
        for (int index = 0; index + 1 < placeholders.length; index += 2) {
            String key = String.valueOf(placeholders[index]);
            String value = String.valueOf(placeholders[index + 1]);
            result = result.replace("{" + key + "}", escape(value));
        }
        return result;
    }

    /** Escapes MiniMessage tag delimiters and the escape character itself. */
    public static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("<", "\\<");
    }

    /** Convenience for localised enums: {@code job.MINER.name}. */
    public String enumKey(String root, Enum<?> value) {
        return root + "." + value.name().toLowerCase(Locale.ROOT);
    }

    public Map<String, String> asMap() {
        return Map.copyOf(messages);
    }
}
