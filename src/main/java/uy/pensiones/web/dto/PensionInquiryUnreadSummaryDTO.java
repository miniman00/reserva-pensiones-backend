package uy.pensiones.web.dto;

public record PensionInquiryUnreadSummaryDTO(
        long received,
        long sent,
        long total
) {}
