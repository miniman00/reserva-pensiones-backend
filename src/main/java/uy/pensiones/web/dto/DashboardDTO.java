package uy.pensiones.web.dto;

import java.util.List;
import java.time.OffsetDateTime;

public record DashboardDTO(
        AccountSummary account,
        OwnerSummary owner,
        List<PensionActivity> ownedPensions,
        List<CollaborationActivity> collaborations,
        List<PensionInquiryDTO> recentInquiries
) {
    public record AccountSummary(
            long favoritesSaved,
            long inquiriesSent
    ) {}

    public record OwnerSummary(
            long pensions,
            int totalCapacity,
            int totalAvailable,
            long favoritesReceived,
            long inquiriesTotal,
            long inquiriesNew,
            long inquiriesContacted,
            long inquiriesClosed,
            long viewsTotal,
            long viewsLast30Days,
            long favoritesLast30Days,
            long inquiriesLast30Days,
            double inquiryConversionLast30Days
    ) {}

    public record PensionActivity(
            Long id,
            String name,
            String city,
            String state,
            Integer capacitySimple,
            Integer capacityMatrimonial,
            Integer availableSimple,
            Integer availableMatrimonial,
            long favorites,
            long inquiriesTotal,
            long inquiriesNew,
            long inquiriesContacted,
            long inquiriesClosed,
            long viewsTotal,
            long viewsLast30Days,
            long favoritesLast30Days,
            long inquiriesLast30Days,
            double inquiryConversionLast30Days,
            OffsetDateTime availabilityUpdatedAt
    ) {}

    public record CollaborationActivity(
            Long id,
            String name,
            String city,
            String state,
            Integer capacitySimple,
            Integer capacityMatrimonial,
            Integer availableSimple,
            Integer availableMatrimonial,
            boolean canUpdateAvailability,
            OffsetDateTime availabilityUpdatedAt
    ) {}
}
