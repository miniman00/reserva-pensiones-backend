package uy.pensiones.web;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import uy.pensiones.enums.InquiryRoomType;
import uy.pensiones.enums.PensionReportReason;
import uy.pensiones.service.LegalDocuments;
import uy.pensiones.web.dto.PensionInquiryCreateRequest;
import uy.pensiones.web.dto.PensionReportCreateRequest;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class PublicRequestValidationTest {

    private static jakarta.validation.ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    @Test
    void validInquiryWithCurrentLegalVersionsPassesValidation() {
        var request = inquiry("Ana", "ana@example.com", "+598 99 123 456",
                LegalDocuments.TERMS_VERSION, LegalDocuments.PRIVACY_VERSION, true, true);

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void inquiryRejectsOldLegalVersionAndInvalidPhone() {
        var request = inquiry("Ana", null, "abc", "0.9", LegalDocuments.PRIVACY_VERSION, true, true);

        var messages = validator.validate(request).stream().map(v -> v.getMessage()).toList();

        assertThat(messages).anyMatch(message -> message.contains("versión vigente"));
        assertThat(messages).anyMatch(message -> message.contains("teléfono"));
    }

    @Test
    void inquiryRequiresExplicitConsent() {
        var request = inquiry("Ana", "ana@example.com", null,
                LegalDocuments.TERMS_VERSION, LegalDocuments.PRIVACY_VERSION, false, false);

        var messages = validator.validate(request).stream().map(v -> v.getMessage()).toList();

        assertThat(messages).anyMatch(message -> message.contains("Términos"));
        assertThat(messages).anyMatch(message -> message.contains("Privacidad"));
    }

    @Test
    void otherReportReasonRequiresUsefulDetail() {
        var request = new PensionReportCreateRequest(
                PensionReportReason.OTHER,
                "corto",
                null,
                "",
                "visitor-1234567890abcdef",
                true,
                true,
                LegalDocuments.TERMS_VERSION,
                LegalDocuments.PRIVACY_VERSION
        );

        assertThat(validator.validate(request).stream().map(v -> v.getMessage()).toList())
                .anyMatch(message -> message.contains("breve detalle"));
    }

    private PensionInquiryCreateRequest inquiry(String name,
                                                 String email,
                                                 String phone,
                                                 String termsVersion,
                                                 String privacyVersion,
                                                 Boolean termsAccepted,
                                                 Boolean privacyAccepted) {
        return new PensionInquiryCreateRequest(
                name,
                email,
                phone,
                InquiryRoomType.ANY,
                LocalDate.now().plusDays(10),
                "Quisiera conocer la disponibilidad.",
                "",
                "visitor-1234567890abcdef",
                null,
                termsAccepted,
                privacyAccepted,
                termsVersion,
                privacyVersion
        );
    }
}
