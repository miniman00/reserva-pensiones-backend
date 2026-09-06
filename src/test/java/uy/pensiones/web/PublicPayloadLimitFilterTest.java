package uy.pensiones.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class PublicPayloadLimitFilterTest {

    private final PublicPayloadLimitFilter filter = new PublicPayloadLimitFilter(1024, new ObjectMapper().findAndRegisterModules());

    @Test
    void smallInquiryPayloadPassesAndBodyRemainsReadable() throws Exception {
        MockHttpServletRequest request = request("/api/public/pensions/42/inquiries", "{\"name\":\"Ana\"}");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> bodySeen = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) ->
                bodySeen.set(new String(req.getInputStream().readAllBytes(), StandardCharsets.UTF_8)));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(bodySeen.get()).isEqualTo("{\"name\":\"Ana\"}");
    }

    @Test
    void oversizedPublicPayloadReturns413WithoutCallingController() throws Exception {
        MockHttpServletRequest request = request("/api/public/pensions/42/reports", "x".repeat(1025));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<Boolean> called = new AtomicReference<>(false);

        filter.doFilter(request, response, (req, res) -> called.set(true));

        assertThat(called.get()).isFalse();
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("PAYLOAD_TOO_LARGE");
    }

    @Test
    void unrelatedPostIsNotBufferedByThisFilter() throws Exception {
        MockHttpServletRequest request = request("/api/pensions", "x".repeat(2048));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<Boolean> called = new AtomicReference<>(false);

        filter.doFilter(request, response, (req, res) -> called.set(true));

        assertThat(called.get()).isTrue();
    }

    private MockHttpServletRequest request(String servletPath, String body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", servletPath);
        request.setServletPath(servletPath);
        request.setContentType("application/json");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }
}
