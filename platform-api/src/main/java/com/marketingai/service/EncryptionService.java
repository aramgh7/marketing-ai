package com.marketingai.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * AES-256-GCM envelope encryption (vision §12.7).
 * Production: set ENCRYPTION_KEY env var (Base64-encoded 32-byte key).
 * Dev: ephemeral key generated at startup (data lost on restart — expected in dev).
 */
@Slf4j
@Service
public class EncryptionService {

    private final SecretKey dataKey;
    private final boolean configured;

    public EncryptionService(@Value("${app.encryption.key:}") String rawKey) {
        configured = !rawKey.isEmpty();
        if (rawKey.isEmpty()) {
            log.warn("EncryptionService: ENCRYPTION_KEY not set — using ephemeral dev key (data not persistent across restarts)");
            try {
                KeyGenerator kg = KeyGenerator.getInstance("AES");
                kg.init(256);
                dataKey = kg.generateKey();
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException("AES KeyGenerator unavailable", e);
            }
        } else {
            byte[] keyBytes = Base64.getDecoder().decode(rawKey);
            dataKey = new SecretKeySpec(keyBytes, "AES");
        }
    }

    /** True when a persistent key was supplied (ENCRYPTION_KEY); false = ephemeral dev key. */
    public boolean isConfigured() {
        return configured;
    }

    public String encrypt(String plaintext) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);
            cipher.init(Cipher.ENCRYPT_MODE, dataKey, new GCMParameterSpec(128, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined   = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv,         0, combined, 0,         iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new RuntimeException("Encryption failed", e);
        }
    }

    public String decrypt(String encryptedBase64) {
        try {
            byte[] combined   = Base64.getDecoder().decode(encryptedBase64);
            byte[] iv         = Arrays.copyOfRange(combined, 0, 12);
            byte[] ciphertext = Arrays.copyOfRange(combined, 12, combined.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, dataKey, new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Decryption failed", e);
        }
    }
}
