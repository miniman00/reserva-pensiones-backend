package uy.pensiones.service;

import org.springframework.stereotype.Component;
import uy.pensiones.model.PensionMedia;
import uy.pensiones.storage.MediaStorage;
import uy.pensiones.storage.MediaStorageKeys;
import uy.pensiones.web.dto.PensionMediaDTO;

@Component
public class PensionMediaDtoMapper {
    private final MediaStorage storage;

    public PensionMediaDtoMapper(MediaStorage storage) {
        this.storage = storage;
    }

    public PensionMediaDTO toDto(PensionMedia media) {
        Long pensionId = media.getPension() == null ? null : media.getPension().getId();
        boolean stored = pensionId != null && media.getFilename() != null && !media.getFilename().isBlank();
        boolean image = stored && media.getKind() == PensionMedia.Kind.IMAGE;
        String originalUrl = stored
                ? storage.deliveryUrl(MediaStorageKeys.original(pensionId, media.getFilename()))
                : media.getUrl();
        return new PensionMediaDTO(
                media.getId(), media.getKind().name(), originalUrl, media.getMimeType(), media.getSizeBytes(),
                media.getWidth(), media.getHeight(), media.getDurationSec(), media.getSortOrder(), media.isCover(),
                image ? PensionImageVariantService.imageUrl(pensionId, media.getFilename(), PensionImageVariantService.Variant.THUMB) : null,
                image ? PensionImageVariantService.imageUrl(pensionId, media.getFilename(), PensionImageVariantService.Variant.CARD) : null,
                image ? PensionImageVariantService.imageUrl(pensionId, media.getFilename(), PensionImageVariantService.Variant.DETAIL) : null,
                image ? PensionImageVariantService.imageUrl(pensionId, media.getFilename(), PensionImageVariantService.Variant.LARGE) : null
        );
    }
}
