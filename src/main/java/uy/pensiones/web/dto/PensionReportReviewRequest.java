package uy.pensiones.web.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import uy.pensiones.enums.PensionReportStatus;

public record PensionReportReviewRequest(
        @NotNull(message = "Selecciona un estado")
        PensionReportStatus status,

        @Size(max = 1500, message = "Las notas no pueden superar 1500 caracteres")
        String adminNotes
) {}
