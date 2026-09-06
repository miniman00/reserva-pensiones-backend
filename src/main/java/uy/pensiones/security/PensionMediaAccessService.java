package uy.pensiones.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import uy.pensiones.repo.PensionRepository;
import uy.pensiones.service.BackofficeSessionService;
import uy.pensiones.service.PensionCatalogQualityService;

@Service
public class PensionMediaAccessService {

    public enum AccessLevel { PUBLIC, PRIVATE, DENIED }

    private final PensionRepository pensions;
    private final Authz authz;
    private final BackofficeSessionService backofficeSessions;
    private final PensionCatalogQualityService catalogQuality;

    public PensionMediaAccessService(PensionRepository pensions,
                                     Authz authz,
                                     BackofficeSessionService backofficeSessions,
                                     PensionCatalogQualityService catalogQuality) {
        this.pensions = pensions;
        this.authz = authz;
        this.backofficeSessions = backofficeSessions;
        this.catalogQuality = catalogQuality;
    }

    public AccessLevel accessLevel(HttpServletRequest request, Long pensionId, String filename) {
        if (pensionId == null || pensionId <= 0 || filename == null || filename.isBlank()) return AccessLevel.DENIED;

        var state = pensions.findMediaAccessState(pensionId, filename, catalogQuality.publicAvailabilityCutoff()).orElse(null);
        if (state == null || !Boolean.TRUE.equals(state.getKnownFile())) return AccessLevel.DENIED;
        if (Boolean.TRUE.equals(state.getPublicVisible())) return AccessLevel.PUBLIC;
        return canViewPrivately(request, pensionId) ? AccessLevel.PRIVATE : AccessLevel.DENIED;
    }

    public boolean canAccess(HttpServletRequest request, Long pensionId, String filename) {
        return accessLevel(request, pensionId, filename) != AccessLevel.DENIED;
    }

    private boolean canViewPrivately(HttpServletRequest request, Long pensionId) {
        // /media/** no pasa por el filtro específico del Backoffice, por eso validamos
        // aquí su cookie opaca sin convertirla en una URL pública.
        String backofficeToken = backofficeSessions.readToken(request);
        if (backofficeToken != null && backofficeSessions.authenticate(backofficeToken).isPresent()) {
            return true;
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) return false;
        Object principal = authentication.getPrincipal();
        return principal instanceof OAuth2User oauth && authz.canViewPension(oauth, pensionId);
    }
}
