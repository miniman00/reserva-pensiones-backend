package uy.pensiones.web.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import uy.pensiones.enums.PensionReportReason;
import uy.pensiones.service.LegalDocuments;

public record PensionReportCreateRequest(
        @NotNull(message = "Selecciona un motivo")
        PensionReportReason reason,

        @Size(max = 1200, message = "El detalle no puede superar 1200 caracteres")
        String details,

        @Email(message = "El email no es válido")
        @Size(max = 190, message = "El email no puede superar 190 caracteres")
        String reporterEmail,

        @Size(max = 160, message = "Dato inválido")
        String website,

        @Size(min = 16, max = 120, message = "Identificador de visitante inválido")
        String visitorKey,

        @NotNull(message = "Debes aceptar los Términos de Uso")
        @AssertTrue(message = "Debes aceptar los Términos de Uso")
        Boolean termsAccepted,

        @NotNull(message = "Debes aceptar la Política de Privacidad")
        @AssertTrue(message = "Debes aceptar la Política de Privacidad para enviar el reporte")
        Boolean privacyAccepted,

        @NotBlank(message = "Versión de Términos requerida")
        @Size(max = 20)
        String termsVersion,

        @NotBlank(message = "Versión de Privacidad requerida")
        @Size(max = 20)
        String privacyVersion
) {
    @AssertTrue(message = "Debes revisar y aceptar la versión vigente de los documentos legales")
    public boolean isLegalVersionCurrent() {
        return LegalDocuments.TERMS_VERSION.equals(termsVersion)
                && LegalDocuments.PRIVACY_VERSION.equals(privacyVersion);
    }

    @AssertTrue(message = "Agrega un breve detalle para este motivo")
    public boolean isDetailsPresentWhenNeeded() {
        if (reason != PensionReportReason.OTHER && reason != PensionReportReason.POSSIBLE_SCAM) return true;
        return details != null && details.trim().length() >= 10;
    }

    @AssertTrue(message = "El detalle contiene caracteres no permitidos")
    public boolean isDetailsSafe() {
        if (details == null) return true;
        return details.chars().noneMatch(ch -> ch < 32 && ch != '\n' && ch != '\r' && ch != '\t');
    }
}
