package uy.pensiones.service;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BackofficeLoginRateLimiterTest {

    @Test
    void limitsPasswordSprayingByIpAcrossDifferentUsernames() {
        BackofficeLoginRateLimiter limiter = new BackofficeLoginRateLimiter(
                2, Duration.ofMinutes(10), 20, Duration.ofMinutes(15));
        MockHttpServletRequest request = request("203.0.113.10");

        limiter.checkAndRecord(request, "admin-a");
        limiter.checkAndRecord(request, "admin-b");

        assertThatThrownBy(() -> limiter.checkAndRecord(request, "admin-c"))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex.getStatusCode().value()).isEqualTo(429);
                    assertThat(ex.getReason()).doesNotContain("IP").doesNotContain("usuario");
                });
    }

    @Test
    void limitsTargetedUsernameAcrossDifferentIps() {
        BackofficeLoginRateLimiter limiter = new BackofficeLoginRateLimiter(
                20, Duration.ofMinutes(10), 2, Duration.ofMinutes(15));

        limiter.checkAndRecord(request("203.0.113.11"), "soporte");
        limiter.checkAndRecord(request("203.0.113.12"), "Soporte");

        assertThatThrownBy(() -> limiter.checkAndRecord(request("203.0.113.13"), " soporte "))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode().value()).isEqualTo(429));
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThatThrownBy(() -> new BackofficeLoginRateLimiter(
                0, Duration.ofMinutes(10), 10, Duration.ofMinutes(15)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new BackofficeLoginRateLimiter(
                10, Duration.ofDays(2), 10, Duration.ofMinutes(15)))
                .isInstanceOf(IllegalStateException.class);
    }

    private MockHttpServletRequest request(String address) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/backoffice/auth/login");
        request.setRemoteAddr(address);
        return request;
    }
}
