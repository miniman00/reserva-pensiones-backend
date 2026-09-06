package uy.pensiones.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import uy.pensiones.enums.BackofficeRole;
import uy.pensiones.model.BackofficeSession;
import uy.pensiones.model.BackofficeUser;

@Component
public class BackofficeMfaPolicy {
    private final boolean enabled;
    private final String issuer;

    public BackofficeMfaPolicy(@Value("${app.backoffice.mfa.enabled:false}") boolean enabled,
                               @Value("${app.backoffice.mfa.issuer:Pensiones Backoffice}") String issuer) {
        this.enabled = enabled;
        String normalizedIssuer = issuer == null || issuer.isBlank() ? "Pensiones Backoffice" : issuer.trim();
        if (normalizedIssuer.length() > 80 || normalizedIssuer.contains("\n") || normalizedIssuer.contains("\r")) {
            throw new IllegalStateException("app.backoffice.mfa.issuer debe tener entre 1 y 80 caracteres y no contener saltos de línea");
        }
        this.issuer = normalizedIssuer;
    }

    public boolean enabled() { return enabled; }
    public String issuer() { return issuer; }

    public boolean requiredFor(BackofficeUser user) {
        if (!enabled || user == null) return false;
        return user.getRole() == BackofficeRole.ADMIN || user.getRole() == BackofficeRole.SUPER_ADMIN;
    }

    public boolean enrolled(BackofficeUser user) {
        return user != null && user.getMfaEnabledAt() != null && user.getMfaSecretCiphertext() != null
                && !user.getMfaSecretCiphertext().isBlank();
    }

    public boolean secondFactorNeeded(BackofficeUser user) {
        return enabled && (requiredFor(user) || enrolled(user));
    }

    public boolean satisfied(BackofficeUser user, BackofficeSession session) {
        return !secondFactorNeeded(user) || (enrolled(user) && session != null && session.getMfaVerifiedAt() != null);
    }

    public boolean enrollmentRequired(BackofficeUser user) {
        return requiredFor(user) && !enrolled(user);
    }
}
