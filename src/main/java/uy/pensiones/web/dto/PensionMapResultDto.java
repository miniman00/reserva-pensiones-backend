package uy.pensiones.web.dto;

import java.util.List;

public record PensionMapResultDto(
        List<PensionCardDto> content,
        long totalElements,
        boolean truncated
) {}
