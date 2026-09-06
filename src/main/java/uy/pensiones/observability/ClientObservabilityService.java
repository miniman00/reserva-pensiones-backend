package uy.pensiones.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import uy.pensiones.web.dto.ClientObservabilityRequest;

import java.util.Locale;
import java.util.Set;

@Service
public class ClientObservabilityService {

    private static final Logger log = LoggerFactory.getLogger(ClientObservabilityService.class);
    private static final Set<ClientObservabilityRequest.Type> VITALS = Set.of(
            ClientObservabilityRequest.Type.LCP,
            ClientObservabilityRequest.Type.CLS,
            ClientObservabilityRequest.Type.FCP,
            ClientObservabilityRequest.Type.TTFB,
            ClientObservabilityRequest.Type.LONG_TASK
    );

    private final MeterRegistry registry;
    private final boolean enabled;

    public ClientObservabilityService(MeterRegistry registry,
                                      @Value("${app.observability.client-events-enabled:true}") boolean enabled) {
        this.registry = registry;
        this.enabled = enabled;
    }

    public void record(ClientObservabilityRequest request, String requestId) {
        if (!enabled || request == null || request.events() == null) return;
        for (ClientObservabilityRequest.Event event : request.events()) {
            if (event == null || event.type() == null) continue;
            String route = routeGroup(event.route());
            String rating = rating(event.rating());
            if (VITALS.contains(event.type())) {
                double value = sanitizeValue(event.value());
                DistributionSummary.builder("pensiones.client.performance")
                        .description("Métricas de rendimiento observadas en el navegador")
                        .tags("metric", event.type().name(), "rating", rating, "route", route)
                        .publishPercentileHistogram()
                        .register(registry)
                        .record(value);
                if ("poor".equals(rating)) {
                    log.warn("client_performance_poor metric={} value={} route={} requestId={}",
                            event.type(), value, route, requestId);
                }
            } else {
                Counter.builder("pensiones.client.error")
                        .description("Errores técnicos reportados por el portal público")
                        .tags("type", event.type().name(), "route", route)
                        .register(registry)
                        .increment();
                log.warn("client_error type={} route={} requestId={}", event.type(), route, requestId);
            }
        }
    }

    static String routeGroup(String raw) {
        String route = raw == null ? "/other" : raw.trim();
        if (route.isEmpty()) return "/other";
        if (route.equals("/")) return "/";
        if (route.startsWith("/pensions/")) return "/pensions/:id";
        if (route.startsWith("/profile/pensions/")) return "/profile/pensions/:id";
        if (route.startsWith("/profile/")) {
            String[] parts = route.split("/");
            return parts.length >= 3 ? "/profile/" + safeSegment(parts[2]) : "/profile";
        }
        if (route.equals("/profile")) return "/profile";
        if (route.equals("/login") || route.equals("/terms") || route.equals("/privacy") || route.equals("/legal/accept")) {
            return route;
        }
        return "/other";
    }

    private static String safeSegment(String value) {
        String normalized = value == null ? "other" : value.toLowerCase(Locale.ROOT);
        return normalized.matches("[a-z0-9-]{1,32}") ? normalized : "other";
    }

    private static String rating(String raw) {
        String value = raw == null ? "unknown" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "good", "needs-improvement", "poor" -> value;
            default -> "unknown";
        };
    }

    private static double sanitizeValue(Double raw) {
        if (raw == null || !Double.isFinite(raw) || raw < 0D) return 0D;
        return Math.min(raw, 120_000D);
    }
}
