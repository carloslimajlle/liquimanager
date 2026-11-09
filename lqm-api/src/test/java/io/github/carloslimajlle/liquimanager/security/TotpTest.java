package io.github.carloslimajlle.liquimanager.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** TOTP as an authenticator app does it (RFC 6238). */
class TotpTest {

    /** The RFC's own secret, "12345678901234567890", in Base32. */
    private static final String SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    @Test
    @DisplayName("it matches the RFC 6238 test vectors (the last six digits of each)")
    void rfcVectors() {
        assertThat(Totp.codeAt(SECRET, Instant.ofEpochSecond(59))).isEqualTo("287082");
        assertThat(Totp.codeAt(SECRET, Instant.ofEpochSecond(1111111109L))).isEqualTo("081804");
        assertThat(Totp.codeAt(SECRET, Instant.ofEpochSecond(2000000000L))).isEqualTo("279037");
    }

    @Test
    @DisplayName("a code is accepted one step either side of now, not two, and a malformed one never")
    void window() {
        Instant now = Instant.ofEpochSecond(1_700_000_000L);
        long step = now.getEpochSecond() / 30;

        assertThat(Totp.matchingStep(SECRET, Totp.codeAt(SECRET, now), now)).isEqualTo(step);
        assertThat(Totp.matchingStep(SECRET, Totp.codeAt(SECRET, now.minusSeconds(30)), now)).isEqualTo(step - 1);
        assertThat(Totp.matchingStep(SECRET, Totp.codeAt(SECRET, now.plusSeconds(30)), now)).isEqualTo(step + 1);
        assertThat(Totp.matchingStep(SECRET, Totp.codeAt(SECRET, now.plusSeconds(90)), now)).isEqualTo(-1);
        assertThat(Totp.matchingStep(SECRET, "12345", now)).isEqualTo(-1);
        assertThat(Totp.matchingStep(SECRET, "abcdef", now)).isEqualTo(-1);
        assertThat(Totp.matchingStep(SECRET, null, now)).isEqualTo(-1);
    }

    @Test
    @DisplayName("the secret is read the way an authenticator shows it: any case, spaces, dashes")
    void base32IsForgiving() {
        assertThat(Totp.decode("gezd gnbv-GY3T QOJQ")).isEqualTo("1234567890".getBytes());
    }
}
