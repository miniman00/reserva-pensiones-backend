package uy.pensiones.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import uy.pensiones.enums.BackofficeRole;
import uy.pensiones.model.BackofficeUser;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Principal exclusivo del Backoffice; nunca representa a un usuario OAuth del marketplace. */
public record BackofficePrincipal(
        Long id,
        Long sessionId,
        String username,
        String displayName,
        String email,
        BackofficeRole role,
        boolean mustChangePassword,
        boolean systemManaged,
        boolean mfaSatisfied,
        int sessionVersion,
        String csrfToken
) implements Serializable {

    public static BackofficePrincipal from(BackofficeUser user, Long sessionId, String csrfToken, boolean mfaSatisfied) {
        return new BackofficePrincipal(
                user.getId(),
                sessionId,
                user.getUsername(),
                user.getDisplayName(),
                user.getEmail(),
                user.getRole(),
                user.isMustChangePassword(),
                user.isSystemManaged(),
                mfaSatisfied,
                user.getSessionVersion(),
                csrfToken
        );
    }

    public Collection<? extends GrantedAuthority> authorities() {
        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_BACKOFFICE_AUTHENTICATED"));
        if (mustChangePassword || !mfaSatisfied) return List.copyOf(authorities);

        authorities.add(new SimpleGrantedAuthority("ROLE_BACKOFFICE"));
        authorities.add(new SimpleGrantedAuthority("BACKOFFICE_MODERATION_MANAGE"));
        if (role == BackofficeRole.ADMIN || role == BackofficeRole.SUPER_ADMIN) {
            authorities.add(new SimpleGrantedAuthority("BACKOFFICE_USER_MANAGE"));
            authorities.add(new SimpleGrantedAuthority("BACKOFFICE_CATALOG_MANAGE"));
            authorities.add(new SimpleGrantedAuthority("BACKOFFICE_AUDIT_READ"));
            authorities.add(new SimpleGrantedAuthority("BACKOFFICE_COMMERCIAL_MANAGE"));
        }
        if (role == BackofficeRole.SUPER_ADMIN) {
            authorities.add(new SimpleGrantedAuthority("BACKOFFICE_STAFF_MANAGE"));
            authorities.add(new SimpleGrantedAuthority("BACKOFFICE_PAYMENT_CONFIG_MANAGE"));
        }
        return List.copyOf(authorities);
    }

    @Override
    public String toString() {
        return "BackofficePrincipal[id=" + id + ", username=" + username + ", role=" + role
                + ", sessionVersion=" + sessionVersion + "]";
    }
}
