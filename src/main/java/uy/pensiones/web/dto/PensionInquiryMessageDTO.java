package uy.pensiones.web.dto;

import uy.pensiones.enums.InquiryMessageSenderRole;
import uy.pensiones.model.PensionInquiryMessage;

import java.time.OffsetDateTime;

public record PensionInquiryMessageDTO(
        Long id,
        InquiryMessageSenderRole senderRole,
        String body,
        OffsetDateTime createdAt
) {
    public static PensionInquiryMessageDTO of(PensionInquiryMessage message) {
        return new PensionInquiryMessageDTO(
                message.getId(),
                message.getSenderRole(),
                message.getBody(),
                message.getCreatedAt()
        );
    }
}
