package uy.pensiones.web.dto;

import uy.pensiones.enums.AdmissionType;
import uy.pensiones.enums.Amenity;
import uy.pensiones.enums.BathroomType;
import uy.pensiones.enums.ResidentProfile;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Set;

public record PensionCardDto(
        Long id,
        String name,
        String city,
        String neighborhood,
        String countryCode,
        Double lat,
        Double lng,
        Integer availableSimple,
        Integer availableMatrimonial,
        BigDecimal priceSimple,
        BigDecimal priceMatrimonial,
        AdmissionType admissionType,
        ResidentProfile residentProfile,
        Set<String> studyCenters,
        Integer bathroomsCount,
        BathroomType bathroomType,
        Boolean hasParking,
        Set<Amenity> amenities,
        Boolean featured,
        Long featuredPromotionId,
        String featuredImageUrl,
        OffsetDateTime availabilityUpdatedAt
) {}
