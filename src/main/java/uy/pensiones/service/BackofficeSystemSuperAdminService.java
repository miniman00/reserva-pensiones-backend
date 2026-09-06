package uy.pensiones.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Credencial de recuperación administrada por infraestructura.
 * El hash BCrypt real nunca se persiste en backoffice_users.
 */
@Service
public class BackofficeSystemSuperAdminService {

    private static final Pattern BCRYPT_HASH = Pattern.compile("^\\$2[aby]\\$(?:0[4-9]|[12][0-9]|3[01])\\$[./A-Za-z0-9]{53}$");

    private final PasswordEncoder passwordEncoder;
    private final String username;
    private final String passwordHash;
    private final String displayName;
    private final String email;
    private final boolean required;

    public BackofficeSystemSuperAdminService(
            PasswordEncoder passwordEncoder,
            @Value("${app.backoffice.super-admin.username:${APP_BACKOFFICE_SUPER_ADMIN_USERNAME:soporte}}") String username,
            @Value("${app.backoffice.super-admin.password-hash:${APP_BACKOFFICE_SUPER_ADMIN_PASSWORD_HASH:}}") String passwordHash,
            @Value("${app.backoffice.super-admin.display-name:${APP_BACKOFFICE_SUPER_ADMIN_DISPLAY_NAME:Soporte}}") String displayName,
            @Value("${app.backoffice.super-admin.email:${APP_BACKOFFICE_SUPER_ADMIN_EMAIL:}}") String email,
            @Value("${app.backoffice.super-admin.required:false}") boolean required
    ) {
        this.passwordEncoder = passwordEncoder;
        this.username = normalizeConfiguredUsername(username);
        this.passwordHash = passwordHash == null ? "" : passwordHash.trim();
        this.displayName = displayName == null || displayName.isBlank() ? "Soporte" : displayName.trim();
        this.email = email == null || email.isBlank() ? null : email.trim().toLowerCase(Locale.ROOT);
        this.required = required;

        if (!this.passwordHash.isBlank() && !BCRYPT_HASH.matcher(this.passwordHash).matches()) {
            throw new IllegalStateException("APP_BACKOFFICE_SUPER_ADMIN_PASSWORD_HASH debe contener un hash BCrypt válido");
        }
        if (this.required && this.passwordHash.isBlank()) {
            throw new IllegalStateException(
                    "APP_BACKOFFICE_SUPER_ADMIN_PASSWORD_HASH es obligatorio cuando app.backoffice.super-admin.required=true");
        }
    }

    public boolean isConfigured() {
        return !passwordHash.isBlank();
    }

    public boolean isSystemUsername(String rawUsername) {
        return rawUsername != null && username.equals(normalizeLoginUsername(rawUsername));
    }

    public boolean matches(String rawPassword) {
        return isConfigured() && passwordEncoder.matches(rawPassword == null ? "" : rawPassword, passwordHash);
    }

    /** Huella de la credencial externa; no permite validar contraseñas sin conocer el hash BCrypt original. */
    public String credentialFingerprint() {
        if (!isConfigured()) return null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(passwordHash.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 no está disponible", ex);
        }
    }

    public String username() {
        return username;
    }

    public String displayName() {
        return displayName;
    }

    public String email() {
        return email;
    }

    public boolean required() {
        return required;
    }

    private String normalizeConfiguredUsername(String value) {
        String normalized = normalizeLoginUsername(value == null || value.isBlank() ? "soporte" : value);
        if (normalized.length() > 80) {
            throw new IllegalStateException("APP_BACKOFFICE_SUPER_ADMIN_USERNAME no puede superar 80 caracteres");
        }
        return normalized;
    }

    private String normalizeLoginUsername(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
