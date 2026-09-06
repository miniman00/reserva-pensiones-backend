package uy.pensiones.web.dto;

import uy.pensiones.enums.PensionStatus;

public record PensionPreviewDTO(
        PensionStatus status,
        PensionPublicationReportDTO validation,
        PublicPensionDetailDto pension
) {}
