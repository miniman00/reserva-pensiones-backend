package uy.pensiones.payment;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import uy.pensiones.config.AppProperties;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class PaymentSecretCrypto {
    private static final String VERSION = "v1";
    private final byte[] key;
    private final SecureRandom random = new SecureRandom();

    public PaymentSecretCrypto(AppProperties properties) {
        String raw = properties.getPayments().getSecretsMasterKey();
        this.key = parseKey(raw);
    }

    public boolean isReady() { return key != null; }

    public String encrypt(String plaintext) {
        if (!isReady()) throw unavailable();
        if (plaintext == null || plaintext.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La credencial no puede estar vacía");
        try {
            byte[] iv = new byte[12]; random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] packed = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, packed, 0, iv.length);
            System.arraycopy(encrypted, 0, packed, iv.length, encrypted.length);
            return VERSION + ":" + Base64.getEncoder().encodeToString(packed);
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo cifrar una credencial de pagos", e);
        }
    }

    public String decrypt(String encoded) {
        if (!isReady()) throw unavailable();
        if (encoded == null || !encoded.startsWith(VERSION + ":")) throw new IllegalStateException("Formato de credencial cifrada no soportado");
        try {
            byte[] packed = Base64.getDecoder().decode(encoded.substring(3));
            if (packed.length <= 12) throw new IllegalStateException("Credencial cifrada inválida");
            byte[] iv = java.util.Arrays.copyOfRange(packed, 0, 12);
            byte[] encrypted = java.util.Arrays.copyOfRange(packed, 12, packed.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo descifrar una credencial de pagos", e);
        }
    }

    public String fingerprint(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public String maskedSuffix(String value) {
        if (value == null || value.isEmpty()) return null;
        if (value.length() <= 8) return null;
        return value.substring(value.length() - 4);
    }

    private byte[] parseKey(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            byte[] decoded = Base64.getDecoder().decode(raw.trim());
            if (decoded.length != 32) throw new IllegalArgumentException("La clave debe decodificar exactamente 32 bytes");
            return decoded;
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("APP_PAYMENT_SECRETS_MASTER_KEY debe ser Base64 de exactamente 32 bytes", e);
        }
    }

    private ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "El almacenamiento seguro de credenciales no está disponible porque falta APP_PAYMENT_SECRETS_MASTER_KEY");
    }
}
