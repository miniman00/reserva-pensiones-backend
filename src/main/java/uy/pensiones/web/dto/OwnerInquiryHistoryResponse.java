package uy.pensiones.web.dto;

import java.time.LocalDate;
import java.util.List;

public record OwnerInquiryHistoryResponse(
        List<PensionInquiryDTO> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        LocalDate from,
        LocalDate to,
        Summary summary,
        List<PensionOption> pensions
) {
    public record Summary(
            long total,
            long newCount,
            long contactedCount,
            long closedCount,
            long convertedCount,
            long noAvailabilityCount,
            long noInterestCount,
            long otherCount,
            double conversionRate
    ) {}

    public record PensionOption(Long id, String name) {}
}
