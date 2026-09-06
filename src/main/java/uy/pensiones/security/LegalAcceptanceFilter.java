package uy.pensiones.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.filter.OncePerRequestFilter;
import uy.pensiones.model.User;
import uy.pensiones.repo.UserRepository;
import uy.pensiones.service.LegalDocuments;
import uy.pensiones.web.error.ApiErrorWriter;

import java.io.IOException;

public class LegalAcceptanceFilter extends OncePerRequestFilter {

    private final UserRepository users;
    private final ObjectMapper objectMapper;

    public LegalAcceptanceFilter(UserRepository users, ObjectMapper objectMapper) {
        this.users = users;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String method = request.getMethod();
        if ("GET".equalsIgnoreCase(method)
                || "HEAD".equalsIgnoreCase(method)
                || "OPTIONS".equalsIgnoreCase(method)) {
            return true;
        }

        String path = request.getRequestURI();
        if (!path.startsWith("/api/")) return true;
        if (path.startsWith("/api/public/")) return true;
        if (path.startsWith("/api/backoffice/")) return true;
        // El Backoffice tiene un contrato de acceso administrativo propio.
        // La aceptación de los términos del marketplace no debe bloquear
        // el bootstrap/login ni las operaciones /api/admin/**.
        if (path.startsWith("/api/admin/")) return true;
        return "/api/legal/accept".equals(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        User user = resolveUser(authentication);

        if (user != null && !LegalDocuments.isCurrent(user)) {
            ApiErrorWriter.write(
                    request,
                    response,
                    objectMapper,
                    org.springframework.http.HttpStatus.valueOf(428),
                    "LEGAL_ACCEPTANCE_REQUIRED",
                    "Debes aceptar los Términos de Uso y la Política de Privacidad vigentes para continuar."
            );
            return;
        }

        filterChain.doFilter(request, response);
    }

    private User resolveUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) return null;
        Object principal = authentication.getPrincipal();
        if (!(principal instanceof OAuth2User oauth2User)) return null;

        User appUser = oauth2User.getAttribute("appUser");
        if (appUser != null) return users.findById(appUser.getId()).orElse(null);

        String email = oauth2User.getAttribute("email");
        return email == null ? null : users.findByEmail(email).orElse(null);
    }
}
