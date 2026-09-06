package uy.pensiones.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import uy.pensiones.service.BackofficeSessionService;
import uy.pensiones.web.error.ApiErrorWriter;

import java.io.IOException;
import java.util.Optional;

/**
 * Carga la identidad interna desde la cookie opaca del Backoffice.
 * Durante /api/admin/** y /api/backoffice/** oculta cualquier principal OAuth del marketplace.
 */
public class BackofficeSessionAuthenticationFilter extends OncePerRequestFilter {

    public static final String CSRF_HEADER = "X-Backoffice-CSRF";

    private final BackofficeSessionService sessions;
    private final ObjectMapper objectMapper;

    public BackofficeSessionAuthenticationFilter(BackofficeSessionService sessions, ObjectMapper objectMapper) {
        this.sessions = sessions;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if ("/api/backoffice/auth/login".equals(path)) return true;
        return !(path.startsWith("/api/backoffice/") || path.startsWith("/api/admin/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContext isolated = SecurityContextHolder.createEmptyContext();
        SecurityContextHolder.setContext(isolated);
        try {
            String rawToken = sessions.readToken(request);
            Optional<BackofficePrincipal> principal = sessions.authenticate(rawToken);
            if (rawToken != null && principal.isEmpty()) {
                sessions.clearCookie(response);
            }

            if (principal.isPresent()) {
                BackofficePrincipal backoffice = principal.get();
                isolated.setAuthentication(new UsernamePasswordAuthenticationToken(
                        backoffice, null, backoffice.authorities()));

                if (requiresCsrf(request)
                        && !sessions.csrfMatches(backoffice, request.getHeader(CSRF_HEADER))) {
                    ApiErrorWriter.write(request, response, objectMapper, HttpStatus.FORBIDDEN,
                            "BACKOFFICE_CSRF_INVALID",
                            "La validación de seguridad de la sesión administrativa falló. Actualiza la página e intenta nuevamente.");
                    return;
                }
            }

            filterChain.doFilter(request, response);
        } finally {
            // Evita que el contexto interno termine persistido dentro del JSESSIONID OAuth del marketplace.
            SecurityContextHolder.setContext(previous);
        }
    }

    private boolean requiresCsrf(HttpServletRequest request) {
        String method = request.getMethod();
        return !(HttpMethod.GET.matches(method)
                || HttpMethod.HEAD.matches(method)
                || HttpMethod.OPTIONS.matches(method));
    }
}
