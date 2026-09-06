package uy.pensiones.service;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import uy.pensiones.model.PensionInquiry;
import uy.pensiones.repo.PensionInquiryRepository;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PensionInquiryProtectionServiceTest {

    @Test
    void rateLimitRejectsThirdRequestForSameClient() {
        PensionInquiryRepository repository = mock(PensionInquiryRepository.class);
        PensionInquiryProtectionService service = service(repository, 2, 10);
        MockHttpServletRequest request = request("203.0.113.7");

        service.checkRateLimit(null, "visitor-1234567890abcdef", request, 50L);
        service.checkRateLimit(null, "visitor-1234567890abcdef", request, 50L);

        assertThatThrownBy(() -> service.checkRateLimit(null, "visitor-1234567890abcdef", request, 50L))
                .isInstanceOf(InquiryRateLimitExceededException.class)
                .satisfies(error -> assertThat(((InquiryRateLimitExceededException) error).getRetryAfterSeconds())
                        .isPositive());
    }

    @Test
    void authenticatedUsersUseTheirAccountAsClientKey() {
        PensionInquiryRepository repository = mock(PensionInquiryRepository.class);
        PensionInquiryProtectionService service = service(repository, 1, 10);

        service.checkRateLimit(7L, "visitor-a-1234567890", request("203.0.113.1"), 50L);

        assertThatThrownBy(() -> service.checkRateLimit(7L, "visitor-b-1234567890", request("203.0.113.2"), 51L))
                .isInstanceOf(InquiryRateLimitExceededException.class);
    }

    @Test
    void duplicateDetectionNormalizesEmailAndPhone() {
        PensionInquiryRepository repository = mock(PensionInquiryRepository.class);
        PensionInquiry existing = new PensionInquiry();
        existing.setContactEmail("Student@Example.com");
        existing.setContactPhone("+598 99 123 456");
        when(repository.findTop20ByPensionIdAndCreatedAtAfterOrderByCreatedAtDesc(eq(50L), any()))
                .thenReturn(List.of(existing));
        PensionInquiryProtectionService service = service(repository, 5, 10);

        assertThat(service.isRecentDuplicate(50L, " student@example.com ", null)).isTrue();
        assertThat(service.isRecentDuplicate(50L, null, "099-123-456")).isTrue();
        assertThat(service.isRecentDuplicate(50L, null, "00598 99 123 456")).isTrue();
        assertThat(service.isRecentDuplicate(50L, null, "99 123 456")).isTrue();
        assertThat(service.isRecentDuplicate(50L, "other@example.com", "099000000")).isFalse();
    }

    private PensionInquiryProtectionService service(PensionInquiryRepository repository, int clientMax, int pensionMax) {
        return new PensionInquiryProtectionService(
                repository,
                clientMax,
                Duration.ofMinutes(10),
                pensionMax,
                Duration.ofMinutes(30),
                50,
                20,
                Duration.ofMinutes(5)
        );
    }

    private MockHttpServletRequest request(String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddr);
        return request;
    }
}
