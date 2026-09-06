package uy.pensiones.web.dto;

import java.util.List;

public record PensionPublicationReportDTO(
        boolean publishable,
        int completenessPercent,
        int completedItems,
        int totalItems,
        List<String> issues,
        List<PensionQualityItemDTO> checklist
) {}
