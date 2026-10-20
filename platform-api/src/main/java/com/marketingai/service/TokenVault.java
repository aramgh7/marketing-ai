package com.marketingai.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Sealed storage for third-party secrets (roadmap 9-1; arch §11 "Meta tokens — AES-encrypted in DB";
 * vision §12.7). Every token column that is declared {@code *_enc BYTEA} goes through this class:
 * <ul>
 *   <li>{@link #seal(String)} — AES-256-GCM via {@link EncryptionService}; the stored bytes are
 *       {@code enc1:} + Base64(iv ‖ ciphertext). Without {@code ENCRYPTION_KEY} the vault stores the
 *       plaintext bytes (dev mode) and logs one warning at startup.</li>
 *   <li>{@link #open(byte[])} — decrypts sealed bytes and passes legacy plaintext rows through
 *       unchanged, so a deployment can enable the key and let {@link TokenReencryptRunner} re-seal
 *       old rows without a downtime window.</li>
 * </ul>
 * The prefix is ASCII, so {@link #isSealed(byte[])} is a cheap check usable from SQL too
 * ({@code substring(col from 1 for 5) = '\x656e63313a'::bytea}).
 */
@Slf4j
@Service
public class TokenVault {

    static final String PREFIX = "enc1:";
    static final byte[] PREFIX_BYTES = PREFIX.getBytes(StandardCharsets.US_ASCII);

    private final EncryptionService encryption;

    public TokenVault(EncryptionService encryption) {
        this.encryption = encryption;
        if (!encryption.isConfigured()) {
            log.warn("TokenVault: ENCRYPTION_KEY not set — third-party tokens are stored unencrypted (dev mode)");
        }
    }

    /** True when sealing is active (a persistent key is configured). */
    public boolean isActive() {
        return encryption.isConfigured();
    }

    /** Seal a secret for storage. Null/blank → null (the column stays empty). */
    public byte[] seal(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) return null;
        if (!encryption.isConfigured()) {
            return plaintext.getBytes(StandardCharsets.UTF_8);
        }
        String sealed = PREFIX + encryption.encrypt(plaintext);
        return sealed.getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * Open stored bytes: sealed rows are decrypted, legacy plaintext rows are returned as-is.
     * Returns null for null/empty input. A sealed row that cannot be decrypted (wrong key, tampering)
     * raises {@link IllegalStateException} — callers must not silently fall back to garbage.
     */
    public String open(byte[] stored) {
        if (stored == null || stored.length == 0) return null;
        if (!isSealed(stored)) {
            return new String(stored, StandardCharsets.UTF_8);
        }
        if (!encryption.isConfigured()) {
            throw new IllegalStateException("Sealed token found but ENCRYPTION_KEY is not configured");
        }
        String payload = new String(stored, PREFIX_BYTES.length, stored.length - PREFIX_BYTES.length,
                StandardCharsets.US_ASCII);
        try {
            return encryption.decrypt(payload);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Sealed token cannot be opened (key mismatch or tampering)", e);
        }
    }

    /** Convenience for optional lookups. */
    public String openOrNull(byte[] stored) {
        try {
            return open(stored);
        } catch (IllegalStateException e) {
            log.error("TokenVault: {}", e.getMessage());
            return null;
        }
    }

    public static boolean isSealed(byte[] stored) {
        if (stored == null || stored.length < PREFIX_BYTES.length) return false;
        return Arrays.equals(Arrays.copyOf(stored, PREFIX_BYTES.length), PREFIX_BYTES);
    }
}
