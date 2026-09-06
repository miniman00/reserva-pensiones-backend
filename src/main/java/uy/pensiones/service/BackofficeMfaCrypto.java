package uy.pensiones.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class BackofficeMfaCrypto {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int IV_BYTES = 12;
    private final boolean enabled;
    private final SecretKeySpec key;

    public BackofficeMfaCrypto(@Value("${app.backoffice.mfa.enabled:false}") boolean enabled,
                               @Value("${app.backoffice.mfa.master-key:}") String encodedKey) {
        this.enabled = enabled;
        if (!enabled) {
            this.key = null;
            return;
        }
        try {
            byte[] raw = Base64.getDecoder().decode(encodedKey == null ? "" : encodedKey.trim());
            if (raw.length != 32) throw new IllegalArgumentException("length");
            this.key = new SecretKeySpec(raw, "AES");
        } catch (Exception ex) {
            throw new IllegalStateException("APP_BACKOFFICE_MFA_MASTER_KEY debe ser Base64 de exactamente 32 bytes cuando MFA está habilitado", ex);
        }
    }

    public String encrypt(String value) {
        if (!enabled || key == null) throw new IllegalStateException("MFA no está habilitado");
        try {
            byte[] iv = new byte[IV_BYTES]; RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(encrypted, 0, payload, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(payload);
        } catch (Exception ex) {
            throw new IllegalStateException("No se pudo cifrar el secreto MFA", ex);
        }
    }

    public String decrypt(String payload) {
        if (!enabled || key == null) throw new IllegalStateException("MFA no está habilitado");
        try {
            byte[] raw = Base64.getDecoder().decode(payload);
            if (raw.length <= IV_BYTES) throw new IllegalArgumentException("payload");
            byte[] iv = java.util.Arrays.copyOfRange(raw, 0, IV_BYTES);
            byte[] encrypted = java.util.Arrays.copyOfRange(raw, IV_BYTES, raw.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalStateException("No se pudo descifrar el secreto MFA", ex);
        }
    }
}
