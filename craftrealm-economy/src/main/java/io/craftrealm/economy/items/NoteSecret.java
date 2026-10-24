package io.craftrealm.economy.items;

import io.craftrealm.core.CraftRealmCore;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Per-install signing key for physical banknotes.
 *
 * <p>A note stores {@code id + amount + signature}. The signature is an HMAC of
 * the first two fields, so an item whose amount was edited by hand (or by another
 * plugin) is rejected before any money is credited. The key lives in
 * {@code plugins/CraftRealm/data/note.key} and is generated on first use.</p>
 */
public final class NoteSecret {

    private final byte[] key;

    private NoteSecret(byte[] key) {
        this.key = key;
    }

    /** Loads the key, creating a new random one when the file is missing. */
    public static NoteSecret load(File file) {
        try {
            if (file.isFile()) {
                byte[] decoded = Base64.getDecoder().decode(Files.readString(file.toPath()).trim());
                if (decoded.length >= 16) {
                    return new NoteSecret(decoded);
                }
            }
            byte[] generated = new byte[32];
            new SecureRandom().nextBytes(generated);
            if (file.getParentFile() != null) {
                file.getParentFile().mkdirs();
            }
            Files.writeString(file.toPath(), Base64.getEncoder().encodeToString(generated));
            return new NoteSecret(generated);
        } catch (IOException | IllegalArgumentException exception) {
            CraftRealmCore.logger().warning("Could not read the note signing key, using an ephemeral one: "
                    + exception.getMessage());
            byte[] fallback = new byte[32];
            new SecureRandom().nextBytes(fallback);
            return new NoteSecret(fallback);
        }
    }

    /** HMAC-SHA256 over {@code id:amount}, hex encoded and truncated for tooltips. */
    public String sign(String id, double amount) {
        String payload = id + ":" + String.format(java.util.Locale.ROOT, "%.2f", amount);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] signature = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(signature).substring(0, 32);
        } catch (Exception exception) {
            throw new IllegalStateException("HMAC unavailable", exception);
        }
    }

    /** Constant-time comparison of a presented signature. */
    public boolean verify(String id, double amount, String presented) {
        if (presented == null) {
            return false;
        }
        return MessageDigest.isEqual(
                sign(id, amount).getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }
}
