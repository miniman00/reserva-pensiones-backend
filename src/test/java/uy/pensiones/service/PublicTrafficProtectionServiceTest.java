package uy.pensiones.service;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PublicTrafficProtectionServiceTest {

    @Test
    void searchRateLimitIsEnforcedPerNetworkKey() {
        PublicTrafficProtectionService service = new PublicTrafficProtectionService(
                2, 2, 2, 2, 2, 2, 10, 10, Duration.ofMinutes(5), 4096);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/public/pensions");
        request.setRemoteAddr("203.0.113.10");

        service.checkSearch(request, false);
        service.checkSearch(request, false);

        assertThatThrownBy(() -> service.checkSearch(request, false))
                .isInstanceOf(InquiryRateLimitExceededException.class);
    }

    @Test
    void oversizedSearchQueryIsRejectedBeforeDatabaseWork() {
        PublicTrafficProtectionService service = new PublicTrafficProtectionService(
                10, 10, 10, 10, 10, 10, 10, 10, Duration.ofMinutes(5), 512);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/public/pensions");
        request.setQueryString("q=" + "x".repeat(600));

        assertThatThrownBy(() -> service.checkSearch(request, false))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("demasiados criterios");
    }

    @Test
    void telemetryIsSilentlyDroppedAfterLimit() {
        PublicTrafficProtectionService service = new PublicTrafficProtectionService(
                10, 10, 10, 10, 10, 10, 2, 2, Duration.ofMinutes(5), 4096);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/public/pensions/1/views");
        request.setRemoteAddr("203.0.113.20");
        String visitor = "0123456789abcdef0123456789abcdef";

        assertThat(service.allowTelemetry(request, visitor)).isTrue();
        assertThat(service.allowTelemetry(request, visitor)).isTrue();
        assertThat(service.allowTelemetry(request, visitor)).isFalse();
    }
    @Test
    void favoriteChangesAreLimitedByUserAndNetwork() {
        PublicTrafficProtectionService service = new PublicTrafficProtectionService(
                10, 10, 10, 10, 2, 10, 10, 10, Duration.ofMinutes(5), 4096);
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/api/favorites/1");
        request.setRemoteAddr("203.0.113.30");

        service.checkFavorite(request, 42L);
        service.checkFavorite(request, 42L);

        assertThatThrownBy(() -> service.checkFavorite(request, 42L))
                .isInstanceOf(InquiryRateLimitExceededException.class);
    }

}
