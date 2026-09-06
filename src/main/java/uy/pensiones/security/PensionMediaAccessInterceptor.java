package uy.pensiones.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import uy.pensiones.service.PublicTrafficProtectionService;

import java.nio.file.Paths;

/** Protege las URLs históricas /media/** sin romperlas. */
@Component
public class PensionMediaAccessInterceptor implements HandlerInterceptor {

    private static final String PREFIX = "/media/pensions/";

    private final PensionMediaAccessService access;
    private final PublicTrafficProtectionService traffic;

    public PensionMediaAccessInterceptor(PensionMediaAccessService access,
                                         PublicTrafficProtectionService traffic) {
        this.access = access;
        this.traffic = traffic;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        ParsedPath parsed = parse(pathWithinApplication(request));
        if (parsed == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return false;
        }
        if (!traffic.allowMedia(request)) {
            response.setHeader("Retry-After", "60");
            response.sendError(429);
            return false;
        }
        response.setHeader("Accept-Ranges", "bytes");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Content-Security-Policy", "default-src 'none'; sandbox");
        PensionMediaAccessService.AccessLevel level = access.accessLevel(request, parsed.pensionId(), parsed.filename());
        if (level == PensionMediaAccessService.AccessLevel.DENIED) {
            // 404 deliberado: no revelamos si una pensión privada/bloqueada existe.
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return false;
        }
        if (level == PensionMediaAccessService.AccessLevel.PUBLIC) {
            response.setHeader("Cache-Control", "public, max-age=300, must-revalidate");
        } else {
            response.setHeader("Cache-Control", "private, no-store");
        }
        return true;
    }

    private ParsedPath parse(String path) {
        if (path == null || !path.startsWith(PREFIX)) return null;
        String remainder = path.substring(PREFIX.length());
        int slash = remainder.indexOf('/');
        if (slash <= 0 || slash == remainder.length() - 1 || remainder.indexOf('/', slash + 1) >= 0) return null;
        try {
            Long pensionId = Long.valueOf(remainder.substring(0, slash));
            if (pensionId <= 0) return null;
            String filename = remainder.substring(slash + 1);
            String safe = Paths.get(filename).getFileName().toString();
            if (!safe.equals(filename) || filename.contains("..") || filename.startsWith(".")) return null;
            return new ParsedPath(pensionId, filename);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private String pathWithinApplication(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && uri.startsWith(context)) {
            return uri.substring(context.length());
        }
        return uri;
    }

    private record ParsedPath(Long pensionId, String filename) {}
}
