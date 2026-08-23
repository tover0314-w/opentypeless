package com.opentypeless.android.editor;

import java.security.MessageDigest;
import java.util.Objects;

/** A typed, lowercase SHA-256 editor fingerprint. */
public record TextFingerprint(FingerprintDomain domain, String sha256Hex) {
    public TextFingerprint {
        Objects.requireNonNull(domain, "domain");
        Objects.requireNonNull(sha256Hex, "sha256Hex");
        if (sha256Hex.length() != EditorSessionLimits.SHA256_HEX_LENGTH) {
            throw new IllegalArgumentException("fingerprint must contain 64 lowercase hex digits");
        }
        for (int index = 0; index < sha256Hex.length(); index++) {
            char value = sha256Hex.charAt(index);
            if (!((value >= '0' && value <= '9') || (value >= 'a' && value <= 'f'))) {
                throw new IllegalArgumentException(
                        "fingerprint must contain 64 lowercase hex digits");
            }
        }
    }

    public boolean securelyMatches(TextFingerprint other) {
        if (other == null || domain != other.domain) return false;
        return MessageDigest.isEqual(decode(sha256Hex), decode(other.sha256Hex));
    }

    private static byte[] decode(String value) {
        byte[] decoded = new byte[value.length() / 2];
        for (int index = 0; index < value.length(); index += 2) {
            decoded[index / 2] = (byte) Integer.parseInt(value.substring(index, index + 2), 16);
        }
        return decoded;
    }

    @Override
    public String toString() {
        return "TextFingerprint{domain=" + domain + ", sha256=<redacted>}";
    }
}
