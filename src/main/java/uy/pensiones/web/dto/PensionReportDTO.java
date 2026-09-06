package uy.pensiones.web.dto;

import uy.pensiones.enums.PensionReportReason;
import uy.pensiones.enums.PensionReportResolution;
import uy.pensiones.enums.PensionReportStatus;
import uy.pensiones.enums.PensionStatus;

import java.time.OffsetDateTime;

public record PensionReportDTO(
        Long id,
        Long pensionId,
        String pensionName,
        PensionStatus pensionStatus,
        boolean moderationBlocked,
        boolean ownerSuspended,
        PensionReportReason reason,
        String details,
        String reporterEmail,
        String reporterName,
        PensionReportStatus status,
        PensionReportResolution resolution,
        String adminNotes,
        String reviewedBy,
        OffsetDateTime reviewedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
