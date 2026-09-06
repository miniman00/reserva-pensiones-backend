package uy.pensiones.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.net.URI;
import java.util.List;

@Configuration
public class CorsConfig {

    @Bean
    CorsConfigurationSource corsConfigurationSource(AppProperties properties, Environment environment) {
        boolean production = environment.acceptsProfiles(Profiles.of("prod"));
        List<String> origins = properties.getCors().getAllowedOrigins().stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> normalizeOrigin(value, production))
                .distinct()
                .toList();

        if (origins.isEmpty()) {
            throw new IllegalStateException("app.cors.allowed-origins debe contener al menos un origen");
        }
        if (origins.contains("*")) {
            throw new IllegalStateException("CORS no admite '*' porque la aplicación utiliza cookies de sesión");
        }

        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(origins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of(
                "Accept", "Content-Type", "X-Requested-With", "X-Request-Id",
                "X-CSRF-TOKEN", "X-Backoffice-CSRF"
        ));
        configuration.setExposedHeaders(List.of("X-Request-Id", "Retry-After"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    private String normalizeOrigin(String value, boolean production) {
        String origin = value.trim().replaceAll("/+$", "");
        URI uri;
        try {
            uri = URI.create(origin);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Origen CORS inválido: " + value, ex);
        }
        String scheme = uri.getScheme();
        String path = uri.getPath();
        if (uri.getHost() == null
                || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                || (path != null && !path.isBlank() && !"/".equals(path))
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null) {
            throw new IllegalStateException("Origen CORS inválido: " + value);
        }
        if (production && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalStateException("En producción todos los orígenes CORS deben usar HTTPS: " + value);
        }
        return origin;
    }
}
