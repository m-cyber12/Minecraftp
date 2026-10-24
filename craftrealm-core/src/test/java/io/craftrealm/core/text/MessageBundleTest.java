package io.craftrealm.core.text;

import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageBundleTest {

    private final MessageBundle bundle = MessageBundle.of(Map.of(
            "greeting", "Hello {player}, you have {amount} Crowns",
            "lines", "first\nsecond",
            "raw", "<gold>Gold"
    ), "en_US");

    @Test
    void substitutesPlaceholders() {
        assertEquals("Hello Steve, you have 10 Crowns", bundle.raw("greeting", "player", "Steve", "amount", 10));
    }

    @Test
    void escapesUntrustedValues() {
        String rendered = bundle.raw("greeting", "player", "<red>Evil", "amount", 0);
        assertEquals("Hello \\<red>Evil, you have 0 Crowns", rendered);
        // The escaped tag must survive to plain text rather than becoming a colour.
        assertEquals("Hello <red>Evil, you have 0 Crowns",
                MessageBundle.plain(MessageBundle.parse(rendered)));
    }

    @Test
    void splitsMultiLineMessages() {
        assertEquals(2, bundle.lines("lines").size());
        assertEquals("second", MessageBundle.plain(bundle.lines("lines").get(1)));
    }

    @Test
    void fallbackBundleFillsGaps() {
        MessageBundle fallback = MessageBundle.of(Map.of("missingKey", "From fallback"), "en_US");
        MessageBundle merged = bundle.mergedWith(fallback);
        assertEquals("From fallback", merged.raw("missingKey"));
        assertTrue(merged.has("greeting"));
    }

    @Test
    void unknownKeysAreVisible() {
        assertTrue(bundle.raw("nope").contains("missing message"));
    }

    @Test
    void parsesMiniMessage() {
        Component component = MessageBundle.parse("<gold>Hi");
        assertEquals("Hi", MessageBundle.plain(component));
    }
}
