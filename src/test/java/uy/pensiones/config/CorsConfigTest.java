package uy.pensiones.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CorsConfigTest {

    @Test
    void allowsBackofficeCsrfHeaderForTrustedOrigin() {
        AppProperties properties = new AppProperties();
        properties.getCors().setAllowedOrigins(List.of("http://localhost:5175"));
        CorsConfig config = new CorsConfig();

        CorsConfiguration cors = config.corsConfigurationSource(properties, new MockEnvironment())
                .getCorsConfiguration(new MockHttpServletRequest("OPTIONS", "/api/admin/dashboard"));

        assertThat(cors).isNotNull();
        assertThat(cors.getAllowedHeaders()).contains("X-CSRF-TOKEN", "X-Backoffice-CSRF");
        assertThat(cors.getAllowCredentials()).isTrue();
    }

    @Test
    void productionRejectsHttpOrigins() {
        AppProperties properties = new AppProperties();
        properties.getCors().setAllowedOrigins(List.of("http://admin.example.com"));
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");

        assertThatThrownBy(() -> new CorsConfig().corsConfigurationSource(properties, environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTPS");
    }

    @Test
    void rejectsOriginsWithEmbeddedCredentialsOrQuery() {
        AppProperties properties = new AppProperties();
        properties.getCors().setAllowedOrigins(List.of("https://user:secret@admin.example.com"));

        assertThatThrownBy(() -> new CorsConfig().corsConfigurationSource(properties, new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Origen CORS inválido");
    }
}
