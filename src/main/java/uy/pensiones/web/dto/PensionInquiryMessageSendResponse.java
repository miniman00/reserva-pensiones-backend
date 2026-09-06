package uy.pensiones.web.dto;

import uy.pensiones.enums.InquiryStatus;

public record PensionInquiryMessageSendResponse(
        PensionInquiryMessageDTO message,
        InquiryStatus status
) {}
