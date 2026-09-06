package uy.pensiones.web.dto;

import uy.pensiones.model.PensionMedia;
import uy.pensiones.service.PensionImageVariantService;

public record PensionMediaDTO(
        Long id,
        String kind,
        String url,
        String mimeType,
        Long sizeBytes,
        Integer width,
        Integer height,
        Integer durationSec,
        Integer sortOrder,
        boolean cover,
        String thumbnailUrl,
        String cardUrl,
        String detailUrl,
        String largeUrl
) {
    public static PensionMediaDTO of(PensionMedia m) {
        Long pensionId = m.getPension() == null ? null : m.getPension().getId();
        boolean image = m.getKind() == PensionMedia.Kind.IMAGE
                && pensionId != null
                && m.getFilename() != null
                && !m.getFilename().isBlank();
        return new PensionMediaDTO(
                m.getId(), m.getKind().name(), m.getUrl(), m.getMimeType(), m.getSizeBytes(),
                m.getWidth(), m.getHeight(), m.getDurationSec(), m.getSortOrder(), m.isCover(),
                image ? PensionImageVariantService.imageUrl(pensionId, m.getFilename(), PensionImageVariantService.Variant.THUMB) : null,
                image ? PensionImageVariantService.imageUrl(pensionId, m.getFilename(), PensionImageVariantService.Variant.CARD) : null,
                image ? PensionImageVariantService.imageUrl(pensionId, m.getFilename(), PensionImageVariantService.Variant.DETAIL) : null,
                image ? PensionImageVariantService.imageUrl(pensionId, m.getFilename(), PensionImageVariantService.Variant.LARGE) : null
        );
    }
}
