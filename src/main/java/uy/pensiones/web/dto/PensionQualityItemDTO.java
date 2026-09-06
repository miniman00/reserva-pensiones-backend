package uy.pensiones.web.dto;

public record PensionQualityItemDTO(
        String key,
        String label,
        boolean required,
        boolean complete,
        String hint
) {}
