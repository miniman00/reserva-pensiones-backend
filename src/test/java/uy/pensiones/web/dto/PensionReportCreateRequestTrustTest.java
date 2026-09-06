package uy.pensiones.web.dto;

import org.junit.jupiter.api.Test;
import uy.pensiones.enums.PensionReportReason;
import uy.pensiones.service.LegalDocuments;

import static org.assertj.core.api.Assertions.assertThat;

class PensionReportCreateRequestTrustTest {

    @Test
    void scamReportsRequireEnoughContextForModeration() {
        PensionReportCreateRequest empty = request(PensionReportReason.POSSIBLE_SCAM, "pago");
        PensionReportCreateRequest explained = request(PensionReportReason.POSSIBLE_SCAM, "Me solicitaron una seña urgente por un canal externo.");

        assertThat(empty.isDetailsPresentWhenNeeded()).isFalse();
        assertThat(explained.isDetailsPresentWhenNeeded()).isTrue();
    }

    private PensionReportCreateRequest request(PensionReportReason reason, String details) {
        return new PensionReportCreateRequest(
                reason, details, null, null, "visitor-123456789", true, true,
                LegalDocuments.TERMS_VERSION, LegalDocuments.PRIVACY_VERSION
        );
    }
}
