package uy.pensiones.web.dto;

import uy.pensiones.enums.InquiryClosureReason;
import uy.pensiones.enums.InquiryRoomType;
import uy.pensiones.enums.InquiryStatus;
import uy.pensiones.model.PensionInquiry;

import java.time.LocalDate;
import java.time.OffsetDateTime;

public record PensionInquiryDTO(
        Long id,
        Long pensionId,
        String pensionName,
        String contactName,
        String contactEmail,
        String contactPhone,
        InquiryRoomType roomType,
        LocalDate moveInDate,
        String message,
        InquiryStatus status,
        InquiryClosureReason closureReason,
        OffsetDateTime closedAt,
        OffsetDateTime convertedAt,
        boolean conversationAvailable,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        long unreadMessages
) {
    public static PensionInquiryDTO of(PensionInquiry inquiry) {
        return of(inquiry, 0);
    }

    public static PensionInquiryDTO of(PensionInquiry inquiry, long unreadMessages) {
        return new PensionInquiryDTO(
                inquiry.getId(),
                inquiry.getPension().getId(),
                inquiry.getPension().getName(),
                inquiry.getContactName(),
                inquiry.getContactEmail(),
                inquiry.getContactPhone(),
                inquiry.getRoomType(),
                inquiry.getMoveInDate(),
                inquiry.getMessage(),
                inquiry.getStatus(),
                inquiry.getClosureReason(),
                inquiry.getClosedAt(),
                inquiry.getConvertedAt(),
                inquiry.getRequester() != null,
                inquiry.getCreatedAt(),
                inquiry.getUpdatedAt(),
                Math.max(0, unreadMessages)
        );
    }
}
