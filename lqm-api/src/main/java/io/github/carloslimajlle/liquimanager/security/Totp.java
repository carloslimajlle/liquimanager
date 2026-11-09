package io.github.carloslimajlle.liquimanager.security;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Time-based one-time passwords (RFC 6238): HMAC-SHA1, 30-second steps, six digits, which is what every authenticator app does.
 *
 * <p>{@link #matchingStep} returns the step a code belongs to, looking one step back and one forward to forgive a clock that is a
 * little off. The caller keeps the last step it accepted, so a code that was used cannot be used again.
 */
public final class Totp {

    static final long STEP_SECONDS = 30;
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final int DIGITS = 6;
    private static final int[] POWERS = {1, 10, 100, 1_000, 10_000, 100_000, 1_000_000};

    private Totp() {
    }

    /** The step a code is valid in at {@code now}, or -1 when it is not valid in the window. */
    public static long matchingStep(String base32Secret, String code, Instant now) {
        if (code == null || !code.matches("\\d{" + DIGITS + "}")) {
            return -1;
        }
        byte[] key = decode(base32Secret);
        long current = now.getEpochSecond() / STEP_SECONDS;
        long matched = -1;
        // Every candidate is computed and compared in constant time, so the answer does not depend on which one matched.
        for (long step = current - 1; step <= current + 1; step++) {
            if (constantTimeEquals(codeFor(key, step), code)) {
                matched = step;
            }
        }
        return matched;
    }

    static String codeFor(byte[] key, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8) | (hash[offset + 3] & 0xff);
            return String.format("%0" + DIGITS + "d", binary % POWERS[DIGITS]);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA1 is not available", e);
        }
    }

    /** The code at {@code now}; for tests and for a person setting the account up. */
    public static String codeAt(String base32Secret, Instant now) {
        return codeFor(decode(base32Secret), now.getEpochSecond() / STEP_SECONDS);
    }

    /** RFC 4648 Base32 without padding, case and spaces ignored (how an authenticator shows a secret). */
    public static byte[] decode(String text) {
        String clean = text.replaceAll("[\\s=-]", "").toUpperCase(Locale.ROOT);
        if (clean.isEmpty()) {
            throw new IllegalArgumentException("empty TOTP secret");
        }
        ByteBuffer out = ByteBuffer.allocate(clean.length() * 5 / 8 + 1);
        int buffer = 0;
        int bits = 0;
        for (char c : clean.toCharArray()) {
            int value = BASE32.indexOf(c);
            if (value < 0) {
                throw new IllegalArgumentException("the TOTP secret is not Base32");
            }
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                out.put((byte) ((buffer >> (bits - 8)) & 0xff));
                bits -= 8;
            }
        }
        byte[] bytes = new byte[out.position()];
        out.flip();
        out.get(bytes);
        return bytes;
    }

    static boolean constantTimeEquals(String a, String b) {
        return java.security.MessageDigest.isEqual(a.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                b.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
