package uy.pensiones.web.dto;

import uy.pensiones.enums.InquiryClosureReason;
import uy.pensiones.enums.InquiryRoomType;
import uy.pensiones.enums.InquiryStatus;
import uy.pensiones.model.Pension;
import uy.pensiones.model.PensionInquiry;
import uy.pensiones.service.PensionImageVariantService;

import java.time.LocalDate;
import java.time.OffsetDateTime;

public record SentPensionInquiryDTO(
        Long id,
        Long pensionId,
        String pensionName,
        String featuredImageUrl,
        InquiryRoomType roomType,
        LocalDate moveInDate,
        String message,
        InquiryStatus status,
        InquiryClosureReason closureReason,
        OffsetDateTime closedAt,
        OffsetDateTime convertedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        boolean conversationAvailable,
        String contactName,
        String contactEmail,
        String contactPhone,
        long unreadMessages
) {
    public static SentPensionInquiryDTO of(PensionInquiry inquiry) {
        return of(inquiry, 0);
    }

    public static SentPensionInquiryDTO of(PensionInquiry inquiry, long unreadMessages) {
        Pension p = inquiry.getPension();
        String image = PensionImageVariantService.cardUrl(p);

        return new SentPensionInquiryDTO(
                inquiry.getId(),
                p.getId(),
                p.getName(),
                image,
                inquiry.getRoomType(),
                inquiry.getMoveInDate(),
                inquiry.getMessage(),
                inquiry.getStatus(),
                inquiry.getClosureReason(),
                inquiry.getClosedAt(),
                inquiry.getConvertedAt(),
                inquiry.getCreatedAt(),
                inquiry.getUpdatedAt(),
                inquiry.getRequester() != null,
                inquiry.getContactName(),
                inquiry.getContactEmail(),
                inquiry.getContactPhone(),
                Math.max(0, unreadMessages)
        );
    }
}
