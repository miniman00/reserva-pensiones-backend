package uy.pensiones.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import uy.pensiones.enums.PromotionExposureEventType;

public record PromotionExposureEventRequest(
        @NotNull Long pensionId,
        @NotNull PromotionExposureEventType eventType,
        @NotBlank String visitorKey
) {}
