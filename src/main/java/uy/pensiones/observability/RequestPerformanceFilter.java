package uy.pensiones.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import uy.pensiones.config.RequestCorrelationFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class RequestPerformanceFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestPerformanceFilter.class);
    private static final Pattern UUID_SEGMENT = Pattern.compile("/[0-9a-fA-F]{8}-[0-9a-fA-F-]{27,36}(?=/|$)");
    private static final Pattern NUMERIC_SEGMENT = Pattern.compile("/\\d+(?=/|$)");
    private static final Pattern TOKEN_SEGMENT = Pattern.compile("/[^/]{24,}(?=/|$)");

    private final MeterRegistry registry;
    private final long slowRequestThresholdMs;

    public RequestPerformanceFilter(MeterRegistry registry,
                                    @Value("${app.observability.slow-request-threshold-ms:1500}") long slowRequestThresholdMs) {
        this.registry = registry;
        this.slowRequestThresholdMs = Math.max(250L, slowRequestThresholdMs);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri == null || !uri.startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long started = System.nanoTime();
        try {
            filterChain.doFilter(request, response);
        } finally {
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            String route = normalizeRoute(request.getRequestURI());
            String method = safeMethod(request.getMethod());
            String status = Integer.toString(response.getStatus());

            Timer.builder("pensiones.api.request.duration")
                    .description("Duración de requests API del portal")
                    .tags("method", method, "route", route, "status", status)
                    .publishPercentileHistogram()
                    .register(registry)
                    .record(Duration.ofMillis(durationMs));

            String requestId = RequestCorrelationFilter.currentRequestId(request);
            if (response.getStatus() >= 500) {
                log.warn("api_request_server_error method={} route={} status={} durationMs={} requestId={}",
                        method, route, response.getStatus(), durationMs, requestId);
            } else if (durationMs >= slowRequestThresholdMs) {
                log.warn("api_request_slow method={} route={} status={} durationMs={} thresholdMs={} requestId={}",
                        method, route, response.getStatus(), durationMs, slowRequestThresholdMs, requestId);
            }
        }
    }

    static String normalizeRoute(String raw) {
        String route = raw == null || raw.isBlank() ? "/unknown" : raw.trim();
        route = UUID_SEGMENT.matcher(route).replaceAll("/{id}");
        route = NUMERIC_SEGMENT.matcher(route).replaceAll("/{id}");
        route = TOKEN_SEGMENT.matcher(route).replaceAll("/{token}");
        return route.length() > 160 ? route.substring(0, 160) : route;
    }

    private static String safeMethod(String raw) {
        String method = raw == null ? "UNKNOWN" : raw.trim().toUpperCase(Locale.ROOT);
        return method.matches("GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS") ? method : "OTHER";
    }
}
