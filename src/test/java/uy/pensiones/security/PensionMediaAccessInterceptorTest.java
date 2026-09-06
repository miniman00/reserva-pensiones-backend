package uy.pensiones.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import uy.pensiones.service.PublicTrafficProtectionService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PensionMediaAccessInterceptorTest {

    @Test
    void publicPathPassesAndCanBeBrieflySharedCached() throws Exception {
        PensionMediaAccessService access = mock(PensionMediaAccessService.class);
        PublicTrafficProtectionService traffic = mock(PublicTrafficProtectionService.class);
        PensionMediaAccessInterceptor interceptor = new PensionMediaAccessInterceptor(access, traffic);
        when(traffic.allowMedia(any())).thenReturn(true);
        when(access.accessLevel(any(), eq(42L), eq("photo.jpg")))
                .thenReturn(PensionMediaAccessService.AccessLevel.PUBLIC);

        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(request("/media/pensions/42/photo.jpg"), response, new Object())).isTrue();
        assertThat(response.getHeader("Cache-Control")).contains("public").contains("max-age=300");
    }

    @Test
    void privatePreviewPassesButIsNeverSharedCached() throws Exception {
        PensionMediaAccessService access = mock(PensionMediaAccessService.class);
        PublicTrafficProtectionService traffic = mock(PublicTrafficProtectionService.class);
        PensionMediaAccessInterceptor interceptor = new PensionMediaAccessInterceptor(access, traffic);
        when(traffic.allowMedia(any())).thenReturn(true);
        when(access.accessLevel(any(), eq(42L), eq("photo.jpg")))
                .thenReturn(PensionMediaAccessService.AccessLevel.PRIVATE);

        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(request("/media/pensions/42/photo.jpg"), response, new Object())).isTrue();
        assertThat(response.getHeader("Cache-Control")).isEqualTo("private, no-store");
    }

    @Test
    void privateOrUnknownFileIsReportedAsNotFound() throws Exception {
        PensionMediaAccessService access = mock(PensionMediaAccessService.class);
        PublicTrafficProtectionService traffic = mock(PublicTrafficProtectionService.class);
        PensionMediaAccessInterceptor interceptor = new PensionMediaAccessInterceptor(access, traffic);
        when(traffic.allowMedia(any())).thenReturn(true);
        when(access.accessLevel(any(), eq(42L), eq("photo.jpg")))
                .thenReturn(PensionMediaAccessService.AccessLevel.DENIED);

        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(request("/media/pensions/42/photo.jpg"), response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(404);
    }

    @Test
    void nestedOrTraversalLikePathsNeverReachAccessService() throws Exception {
        PensionMediaAccessService access = mock(PensionMediaAccessService.class);
        PublicTrafficProtectionService traffic = mock(PublicTrafficProtectionService.class);
        PensionMediaAccessInterceptor interceptor = new PensionMediaAccessInterceptor(access, traffic);

        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(request("/media/pensions/42/.variants/card/a.jpg"), response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(404);
        verifyNoInteractions(access);
    }

    private MockHttpServletRequest request(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        return request;
    }
}
