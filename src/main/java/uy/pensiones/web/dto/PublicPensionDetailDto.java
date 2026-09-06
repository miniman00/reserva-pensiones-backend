package uy.pensiones.web.dto;

import uy.pensiones.enums.AdmissionType;
import uy.pensiones.enums.Amenity;
import uy.pensiones.enums.BathroomType;
import uy.pensiones.enums.ResidentProfile;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

public record PublicPensionDetailDto(
        Long id,
        String name,
        String description,
        String countryCode,
        String addressLine1,
        String city,
        String state,
        String postalCode,
        String neighborhood,
        Double lat,
        Double lng,
        Integer capacitySimple,
        Integer capacityMatrimonial,
        Integer availableSimple,
        Integer availableMatrimonial,
        BigDecimal priceSimple,
        BigDecimal priceMatrimonial,
        AdmissionType admissionType,
        ResidentProfile residentProfile,
        Integer bathroomsCount,
        BathroomType bathroomType,
        Boolean hasParking,
        String contactName,
        String contactPhone,
        String contactWhatsapp,
        Set<Amenity> amenities,
        Set<String> nearbyTags,
        Set<String> studyCenters,
        String featuredImageUrl,
        OffsetDateTime availabilityUpdatedAt,
        PublicPensionTrustDTO trust,
        List<PensionMediaDTO> media
) {}
