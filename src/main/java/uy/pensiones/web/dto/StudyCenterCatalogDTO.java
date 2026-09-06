package uy.pensiones.web.dto;

import uy.pensiones.model.StudyCenterCatalog;

public record StudyCenterCatalogDTO(
        Long id,
        String name,
        String city,
        String countryCode,
        Double lat,
        Double lng,
        boolean verified,
        boolean geolocated
) {
    public static StudyCenterCatalogDTO of(StudyCenterCatalog center) {
        return new StudyCenterCatalogDTO(
                center.getId(),
                center.getName(),
                center.getCity(),
                center.getCountryCode(),
                center.getLat(),
                center.getLng(),
                Boolean.TRUE.equals(center.getVerified()),
                center.getLat() != null && center.getLng() != null
        );
    }
}
