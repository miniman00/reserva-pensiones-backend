package uy.pensiones.service;

import org.springframework.stereotype.Service;
import uy.pensiones.model.Pension;
import uy.pensiones.model.User;
import uy.pensiones.repo.PensionMediaRepository;
import uy.pensiones.web.dto.PensionMediaDTO;
import uy.pensiones.web.dto.PublicPensionDetailDto;
import uy.pensiones.web.dto.PublicPensionTrustDTO;

@Service
public class PensionDetailService {

    private final PensionMediaRepository mediaRepo;
    private final PensionMediaDtoMapper mediaMapper;

    public PensionDetailService(PensionMediaRepository mediaRepo, PensionMediaDtoMapper mediaMapper) {
        this.mediaRepo = mediaRepo;
        this.mediaMapper = mediaMapper;
    }

    public PublicPensionDetailDto toPublicDetail(Pension p) {
        var media = mediaRepo.findByPensionIdOrderBySortOrderAscIdAsc(p.getId())
                .stream()
                .map(mediaMapper::toDto)
                .toList();

        String phone = visibleContact(Boolean.TRUE.equals(p.getShowPhone()), p.getContactPhone());
        String whatsapp = Boolean.TRUE.equals(p.getShowWhatsapp())
                ? PublicContactNumbers.normalizeWhatsapp(p.getContactWhatsapp())
                : null;
        String contactName = (phone != null || whatsapp != null) ? trimToNull(p.getContactName()) : null;

        return new PublicPensionDetailDto(
                p.getId(),
                p.getName(),
                p.getDescription(),
                p.getCountryCode(),
                p.getAddressLine1(),
                p.getCity(),
                p.getState(),
                p.getPostalCode(),
                p.getNeighborhood(),
                p.getLat(),
                p.getLng(),
                p.getCapacitySimple(),
                p.getCapacityMatrimonial(),
                p.getAvailableSimple(),
                p.getAvailableMatrimonial(),
                p.getPriceSimple(),
                p.getPriceMatrimonial(),
                p.getAdmissionType(),
                p.getResidentProfile(),
                p.getBathroomsCount(),
                p.getBathroomType(),
                p.getHasParking(),
                contactName,
                phone,
                whatsapp,
                p.getAmenities(),
                p.getNearbyTags(),
                p.getStudyCenters(),
                featuredImageUrl(p),
                p.getAvailabilityUpdatedAt(),
                trustSignals(p),
                media
        );
    }

    private PublicPensionTrustDTO trustSignals(Pension pension) {
        User owner = pension.getOwner() != null ? pension.getOwner() : pension.getCreatedBy();
        return new PublicPensionTrustDTO(
                owner != null && owner.isEmailVerified(),
                owner != null ? owner.getCreatedAt() : null,
                pension.getCreatedAt()
        );
    }

    private String visibleContact(boolean enabled, String value) {
        return enabled ? trimToNull(value) : null;
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String featuredImageUrl(Pension p) {
        return (p.getFeaturedImage() == null || p.getFeaturedImage().isBlank())
                ? null
                : PensionImageVariantService.detailUrl(p);
    }
}
