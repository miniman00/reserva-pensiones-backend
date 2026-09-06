package uy.pensiones.observability;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ObservabilityNormalizationTest {

    @Test
    void normalizesDynamicApiSegmentsWithoutKeepingIdentifiers() {
        assertThat(RequestPerformanceFilter.normalizeRoute("/api/public/pensions/123"))
                .isEqualTo("/api/public/pensions/{id}");
        assertThat(RequestPerformanceFilter.normalizeRoute("/api/invites/abcdefghijklmnopqrstuvwxyz012345/accept"))
                .isEqualTo("/api/invites/{token}/accept");
    }

    @Test
    void groupsClientRoutesToBoundMetricCardinality() {
        assertThat(ClientObservabilityService.routeGroup("/pensions/881"))
                .isEqualTo("/pensions/:id");
        assertThat(ClientObservabilityService.routeGroup("/profile/pensions/55/edit"))
                .isEqualTo("/profile/pensions/:id");
        assertThat(ClientObservabilityService.routeGroup("/profile/favorites"))
                .isEqualTo("/profile/favorites");
        assertThat(ClientObservabilityService.routeGroup("/unexpected/free-form-value"))
                .isEqualTo("/other");
    }
}
