package uy.pensiones.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RequestCorrelationFilterTest {

    private final RequestCorrelationFilter filter = new RequestCorrelationFilter();

    @Test
    void preservesSafeIncomingRequestId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/public/pensions");
        request.addHeader(RequestCorrelationFilter.HEADER, "web-123_test");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> insideChain = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) ->
                insideChain.set(RequestCorrelationFilter.currentRequestId((jakarta.servlet.http.HttpServletRequest) req)));

        assertThat(insideChain.get()).isEqualTo("web-123_test");
        assertThat(response.getHeader(RequestCorrelationFilter.HEADER)).isEqualTo("web-123_test");
    }

    @Test
    void replacesUnsafeRequestId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/public/pensions");
        request.addHeader(RequestCorrelationFilter.HEADER, "bad request id with spaces");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> { });

        String generated = response.getHeader(RequestCorrelationFilter.HEADER);
        assertThat(generated).isNotBlank().doesNotContain(" ");
        assertThat(generated).matches("[A-Za-z0-9._-]{1,64}");
    }
}
