package com.marketingai.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Roadmap 9-1: sealed secrets — round trip, legacy passthrough, tamper detection, dev mode. */
class TokenVaultTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);   // test-only key

    /** Helper: create EncryptionService matching the two-arg constructor (requireKey=false in tests). */
    private static EncryptionService enc(String key) {
        return new EncryptionService(key, false);
    }

    @Test
    void seal_then_open_roundTrips_and_isPrefixed() {
        TokenVault vault = new TokenVault(enc(KEY));
        byte[] sealed = vault.seal("EAAB-meta-token-123");
        assertThat(TokenVault.isSealed(sealed)).isTrue();
        assertThat(new String(sealed, StandardCharsets.US_ASCII)).startsWith("enc1:");
        assertThat(vault.open(sealed)).isEqualTo("EAAB-meta-token-123");
        assertThat(vault.isActive()).isTrue();
    }

    @Test
    void legacy_plaintext_rows_open_unchanged() {
        TokenVault vault = new TokenVault(enc(KEY));
        byte[] legacy = "plain-old-token".getBytes(StandardCharsets.UTF_8);
        assertThat(TokenVault.isSealed(legacy)).isFalse();
        assertThat(vault.open(legacy)).isEqualTo("plain-old-token");
    }

    @Test
    void tampered_ciphertext_is_rejected_not_returned() {
        TokenVault vault = new TokenVault(enc(KEY));
        byte[] sealed = vault.seal("secret");
        sealed[sealed.length - 3] ^= 0x1;
        assertThatThrownBy(() -> vault.open(sealed)).isInstanceOf(IllegalStateException.class);
        assertThat(vault.openOrNull(sealed)).isNull();
    }

    @Test
    void wrong_key_cannot_open() {
        byte[] sealed = new TokenVault(enc(KEY)).seal("secret");
        byte[] other = new byte[32]; other[0] = 1;
        TokenVault vault2 = new TokenVault(enc(Base64.getEncoder().encodeToString(other)));
        assertThatThrownBy(() -> vault2.open(sealed)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void dev_mode_without_key_stores_plaintext_and_refuses_sealed_rows() {
        TokenVault vault = new TokenVault(enc(""));
        assertThat(vault.isActive()).isFalse();
        byte[] stored = vault.seal("dev-token");
        assertThat(TokenVault.isSealed(stored)).isFalse();
        assertThat(vault.open(stored)).isEqualTo("dev-token");
        byte[] sealedElsewhere = new TokenVault(enc(KEY)).seal("x");
        assertThatThrownBy(() -> vault.open(sealedElsewhere)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void null_and_blank_map_to_null() {
        TokenVault vault = new TokenVault(enc(KEY));
        assertThat(vault.seal(null)).isNull();
        assertThat(vault.seal("  ")).isNull();
        assertThat(vault.open(null)).isNull();
        assertThat(vault.open(new byte[0])).isNull();
    }

    @Test
    void require_key_throws_on_startup_when_key_absent() {
        // S-2/X-4: in production (require-key=true) startup must fail fast if no key is set
        assertThatThrownBy(() -> new EncryptionService("", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ENCRYPTION_KEY");
    }
}
