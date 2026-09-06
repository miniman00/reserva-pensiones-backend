package uy.pensiones.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import uy.pensiones.repo.AdminAuditLogRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class AdminAuditServiceTest {

    private final AdminAuditService service = new AdminAuditService(
            mock(AdminAuditLogRepository.class),
            new ObjectMapper().findAndRegisterModules()
    );

    @Test
    void requiredReasonIsTrimmed() {
        assertThat(service.requireReason("  Corrección solicitada por moderación.  "))
                .isEqualTo("Corrección solicitada por moderación.");
    }

    @Test
    void requiredReasonRejectsBlankValues() {
        assertThatThrownBy(() -> service.requireReason("   "))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("motivo");
    }

    @Test
    void requiredReasonRejectsOversizedValuesInsteadOfTruncatingAuditData() {
        assertThatThrownBy(() -> service.requireReason("x".repeat(AdminAuditService.MAX_REASON_LENGTH + 1)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("superar");
    }
}
