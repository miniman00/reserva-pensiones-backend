package uy.pensiones.web.dto;

import uy.pensiones.enums.PensionStatus;
import uy.pensiones.model.Pension;
import uy.pensiones.model.PensionFavorite;
import uy.pensiones.service.PensionImageVariantService;
import uy.pensiones.service.PublicLocationPrivacy;

import java.time.OffsetDateTime;

public record PensionFavoriteDTO(
        Long favoriteId,
        OffsetDateTime createdAt,
        boolean publiclyVisible,
        PensionCardDto pension
) {
    public static PensionFavoriteDTO of(PensionFavorite favorite) {
        Pension p = favorite.getPension();
        String image = PensionImageVariantService.cardUrl(p);

        PensionCardDto card = new PensionCardDto(
                p.getId(),
                p.getName(),
                p.getCity(),
                p.getNeighborhood(),
                p.getCountryCode(),
                PublicLocationPrivacy.approximateCoordinate(p.getLat()),
                PublicLocationPrivacy.approximateCoordinate(p.getLng()),
                p.getAvailableSimple(),
                p.getAvailableMatrimonial(),
                p.getPriceSimple(),
                p.getPriceMatrimonial(),
                p.getAdmissionType(),
                p.getResidentProfile(),
                p.getStudyCenters(),
                p.getBathroomsCount(),
                p.getBathroomType(),
                p.getHasParking(),
                p.getAmenities(),
                p.getFeatured(),
                null,
                image,
                p.getAvailabilityUpdatedAt()
        );

        return new PensionFavoriteDTO(
                favorite.getId(),
                favorite.getCreatedAt(),
                p.getStatus() == PensionStatus.PUBLISHED
                        && !Boolean.TRUE.equals(p.getModerationBlocked())
                        && !ownerSuspended(p),
                card
        );
    }

    private static boolean ownerSuspended(Pension p) {
        var owner = p.getOwner() != null ? p.getOwner() : p.getCreatedBy();
        return owner == null || owner.isSuspended();
    }
}
