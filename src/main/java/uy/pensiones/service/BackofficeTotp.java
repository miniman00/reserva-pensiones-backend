package uy.pensiones.service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Locale;
import java.util.OptionalLong;

final class BackofficeTotp {
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long STEP_SECONDS = 30L;

    private BackofficeTotp() {}

    static String newSecret() {
        byte[] bytes = new byte[20];
        RANDOM.nextBytes(bytes);
        return encodeBase32(bytes);
    }

    static OptionalLong verify(String base32Secret, String rawCode, Instant now, Long lastAcceptedCounter) {
        String code = normalizeCode(rawCode);
        if (code == null) return OptionalLong.empty();
        long current = now.getEpochSecond() / STEP_SECONDS;
        for (long counter = current - 1; counter <= current + 1; counter++) {
            if (lastAcceptedCounter != null && counter <= lastAcceptedCounter) continue;
            if (constantTimeEquals(code, generate(base32Secret, counter, 6))) return OptionalLong.of(counter);
        }
        return OptionalLong.empty();
    }

    static String generate(String base32Secret, long counter, int digits) {
        try {
            byte[] secret = decodeBase32(base32Secret);
            byte[] message = new byte[8];
            long value = counter;
            for (int i = 7; i >= 0; i--) { message[i] = (byte) value; value >>>= 8; }
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] hash = mac.doFinal(message);
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24)
                    | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8)
                    | (hash[offset + 3] & 0xff);
            int mod = 1;
            for (int i = 0; i < digits; i++) mod *= 10;
            return String.format(Locale.ROOT, "%0" + digits + "d", binary % mod);
        } catch (Exception ex) {
            throw new IllegalStateException("No se pudo calcular TOTP", ex);
        }
    }

    static String encodeBase32(byte[] data) {
        StringBuilder out = new StringBuilder((data.length * 8 + 4) / 5);
        int buffer = 0, bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff); bits += 8;
            while (bits >= 5) { bits -= 5; out.append(ALPHABET.charAt((buffer >> bits) & 31)); }
        }
        if (bits > 0) out.append(ALPHABET.charAt((buffer << (5 - bits)) & 31));
        return out.toString();
    }

    static byte[] decodeBase32(String value) {
        String clean = value == null ? "" : value.replace("=", "").replace(" ", "").toUpperCase(Locale.ROOT);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int buffer = 0, bits = 0;
        for (char c : clean.toCharArray()) {
            int idx = ALPHABET.indexOf(c);
            if (idx < 0) throw new IllegalArgumentException("Secreto Base32 inválido");
            buffer = (buffer << 5) | idx; bits += 5;
            if (bits >= 8) { bits -= 8; out.write((buffer >> bits) & 0xff); }
        }
        return out.toByteArray();
    }

    private static String normalizeCode(String value) {
        if (value == null) return null;
        String code = value.trim().replace(" ", "");
        return code.matches("\\d{6}") ? code : null;
    }

    private static boolean constantTimeEquals(String a, String b) {
        return java.security.MessageDigest.isEqual(a.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                b.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }
}
