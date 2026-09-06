package uy.pensiones.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class LegalAcceptanceFilterTest {

    private final LegalAcceptanceFilter filter = new LegalAcceptanceFilter(null, null);

    @Test
    void backofficeEndpointsDoNotRequireMarketplaceLegalAcceptance() {
        assertThat(filter.shouldNotFilter(request("POST", "/api/backoffice/auth/login"))).isTrue();
        assertThat(filter.shouldNotFilter(request("POST", "/api/admin/users/10/suspend"))).isTrue();
        assertThat(filter.shouldNotFilter(request("PUT", "/api/admin/users/10/role"))).isTrue();
    }

    @Test
    void marketplaceMutationsStillRequireLegalAcceptance() {
        assertThat(filter.shouldNotFilter(request("POST", "/api/pensions"))).isFalse();
        assertThat(filter.shouldNotFilter(request("PATCH", "/api/pensions/10"))).isFalse();
    }

    @Test
    void legalAcceptanceAndReadOnlyRequestsRemainExempt() {
        assertThat(filter.shouldNotFilter(request("POST", "/api/legal/accept"))).isTrue();
        assertThat(filter.shouldNotFilter(request("GET", "/api/pensions/10"))).isTrue();
    }

    private MockHttpServletRequest request(String method, String path) {
        return new MockHttpServletRequest(method, path);
    }
}
