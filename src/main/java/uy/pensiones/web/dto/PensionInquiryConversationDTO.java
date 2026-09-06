package uy.pensiones.web.dto;

import uy.pensiones.enums.InquiryStatus;

import java.util.List;

public record PensionInquiryConversationDTO(
        Long inquiryId,
        InquiryStatus status,
        boolean canReply,
        List<PensionInquiryMessageDTO> messages
) {}
