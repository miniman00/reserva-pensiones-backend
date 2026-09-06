package uy.pensiones.pension;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.jpa.domain.Specification;
import uy.pensiones.enums.AdmissionType;
import uy.pensiones.enums.Amenity;
import uy.pensiones.enums.BathroomType;
import uy.pensiones.enums.PensionStatus;
import uy.pensiones.enums.ResidentProfile;
import uy.pensiones.model.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Set;

public class PensionSpecs {

    public static Specification<Pension> publicSearch(
            String q,
            String countryCode,
            String city,
            String neighborhood,
            RoomType roomType,
            BigDecimal minPrice,
            BigDecimal maxPrice,
            BathroomType bathroomType,
            AdmissionType admissionType,
            ResidentProfile residentProfile,
            String studyCenter,
            Double studyCenterLat,
            Double studyCenterLng,
            Double maxStudyDistanceKm,
            Boolean hasParking,
            Set<Amenity> amenities,
            Double south,
            Double west,
            Double north,
            Double east,
            Double nearLat,
            Double nearLng,
            Double nearRadiusKm,
            OffsetDateTime availabilityCutoff
    ) {
        return (root, query, cb) -> {
            var ownerJoin = root.join("owner", JoinType.LEFT);
            var createdByJoin = root.join("createdBy", JoinType.LEFT);
            var activeOwner = cb.or(
                    cb.and(cb.isNotNull(ownerJoin.get("id")), cb.isFalse(ownerJoin.get("suspended"))),
                    cb.and(cb.isNull(ownerJoin.get("id")), cb.isFalse(createdByJoin.get("suspended")))
            );
            var p = cb.and(
                    cb.equal(root.get("status"), PensionStatus.PUBLISHED),
                    cb.isFalse(root.get("moderationBlocked")),
                    activeOwner,
                    cb.isNotNull(root.get("availabilityUpdatedAt")),
                    cb.greaterThanOrEqualTo(root.<OffsetDateTime>get("availabilityUpdatedAt"), availabilityCutoff)
            );

            if (countryCode != null && !countryCode.isBlank()) {
                p = cb.and(p, cb.equal(cb.upper(root.get("countryCode")), countryCode.toUpperCase()));
            }
            if (city != null && !city.isBlank()) {
                p = cb.and(p, cb.like(cb.lower(root.get("city")), containsPattern(city), '\\'));
            }
            if (neighborhood != null && !neighborhood.isBlank()) {
                p = cb.and(p, cb.like(cb.lower(root.get("neighborhood")), containsPattern(neighborhood), '\\'));
            }

            if (q != null && !q.isBlank()) {
                String like = containsPattern(q);
                p = cb.and(p, cb.or(
                        cb.like(publicSearchText(root, cb), like, '\\'),
                        studyCenterContains(root, query, cb, like)
                ));
            }

            // Disponibilidad (solo listar con habitaciones disponibles)
            if (roomType == RoomType.SIMPLE) {
                p = cb.and(p, cb.greaterThan(root.get("availableSimple"), 0));
            } else if (roomType == RoomType.MATRIMONIAL) {
                p = cb.and(p, cb.greaterThan(root.get("availableMatrimonial"), 0));
            } else {
                // cualquiera de las dos
                p = cb.and(p, cb.or(
                        cb.greaterThan(root.get("availableSimple"), 0),
                        cb.greaterThan(root.get("availableMatrimonial"), 0)
                ));
            }

            // Precio: si roomType está definido, filtramos por ese precio; si no, filtramos por “alguno” (simple o matrimonial)
            if (minPrice != null) {
                if (roomType == RoomType.SIMPLE) {
                    p = cb.and(p, cb.greaterThanOrEqualTo(root.get("priceSimple"), minPrice));
                } else if (roomType == RoomType.MATRIMONIAL) {
                    p = cb.and(p, cb.greaterThanOrEqualTo(root.get("priceMatrimonial"), minPrice));
                } else {
                    p = cb.and(p, cb.or(
                            cb.greaterThanOrEqualTo(root.get("priceSimple"), minPrice),
                            cb.greaterThanOrEqualTo(root.get("priceMatrimonial"), minPrice)
                    ));
                }
            }
            if (maxPrice != null) {
                if (roomType == RoomType.SIMPLE) {
                    p = cb.and(p, cb.lessThanOrEqualTo(root.get("priceSimple"), maxPrice));
                } else if (roomType == RoomType.MATRIMONIAL) {
                    p = cb.and(p, cb.lessThanOrEqualTo(root.get("priceMatrimonial"), maxPrice));
                } else {
                    p = cb.and(p, cb.or(
                            cb.lessThanOrEqualTo(root.get("priceSimple"), maxPrice),
                            cb.lessThanOrEqualTo(root.get("priceMatrimonial"), maxPrice)
                    ));
                }
            }

            if (bathroomType != null) {
                p = cb.and(p, cb.equal(root.get("bathroomType"), bathroomType));
            }
            if (admissionType != null) {
                p = cb.and(p, cb.equal(root.get("admissionType"), admissionType));
            }
            if (residentProfile != null) {
                p = cb.and(p, cb.equal(root.get("residentProfile"), residentProfile));
            }
            if (studyCenter != null && !studyCenter.isBlank() && maxStudyDistanceKm == null) {
                String like = containsPattern(studyCenter);
                p = cb.and(p, studyCenterContains(root, query, cb, like));
            }
            if (maxStudyDistanceKm != null && studyCenterLat != null && studyCenterLng != null) {
                p = cb.and(p, withinDistance(root, cb, studyCenterLat, studyCenterLng, maxStudyDistanceKm));
            }
            if (hasParking != null) {
                p = cb.and(p, cb.equal(root.get("hasParking"), hasParking));
            }


            if (nearLat != null && nearLng != null && nearRadiusKm != null) {
                p = cb.and(p, withinDistance(root, cb, nearLat, nearLng, nearRadiusKm));
            }

            // Zona visible del mapa. Solo se aplica cuando llegaron los cuatro límites.
            if (south != null && west != null && north != null && east != null) {
                p = cb.and(p,
                        cb.isNotNull(root.get("lat")),
                        cb.isNotNull(root.get("lng")),
                        cb.greaterThanOrEqualTo(root.<Double>get("lat"), south),
                        cb.lessThanOrEqualTo(root.<Double>get("lat"), north)
                );

                // Normalmente west <= east. Si cruza el antimeridiano, la longitud
                // queda en dos tramos: [west, 180] U [-180, east].
                if (west <= east) {
                    p = cb.and(p,
                            cb.greaterThanOrEqualTo(root.<Double>get("lng"), west),
                            cb.lessThanOrEqualTo(root.<Double>get("lng"), east)
                    );
                } else {
                    p = cb.and(p, cb.or(
                            cb.greaterThanOrEqualTo(root.<Double>get("lng"), west),
                            cb.lessThanOrEqualTo(root.<Double>get("lng"), east)
                    ));
                }
            }

            // Amenities: EXISTS evita multiplicar filas de la pensión y elimina la necesidad de DISTINCT.
            if (amenities != null && !amenities.isEmpty()) {
                p = cb.and(p, hasAnyAmenity(root, query, cb, amenities));
            }

            return p;
        };
    }

    /**
     * Texto normalizado que PostgreSQL mantiene indexado con pg_trgm. Agrupar los
     * campos escala mucho mejor que ejecutar seis LOWER(... ) LIKE por cada término.
     */
    static Expression<String> publicSearchText(Root<Pension> root, CriteriaBuilder cb) {
        return cb.function(
                "pension_public_search_text",
                String.class,
                root.<String>get("name"),
                root.<String>get("description"),
                root.<String>get("addressLine1"),
                root.<String>get("city"),
                root.<String>get("neighborhood")
        );
    }

    private static Predicate studyCenterContains(Root<Pension> root, CriteriaQuery<?> query,
                                                 CriteriaBuilder cb, String like) {
        var subquery = query.subquery(Long.class);
        var correlated = subquery.correlate(root);
        var studyCenter = correlated.joinSet("studyCenters");
        subquery.select(cb.literal(1L)).where(
                cb.like(cb.lower(studyCenter.as(String.class)), like, '\\')
        );
        return cb.exists(subquery);
    }

    private static Predicate hasAnyAmenity(Root<Pension> root, CriteriaQuery<?> query,
                                           CriteriaBuilder cb, Set<Amenity> amenities) {
        var subquery = query.subquery(Long.class);
        var correlated = subquery.correlate(root);
        var amenity = correlated.joinSet("amenities");
        subquery.select(cb.literal(1L)).where(amenity.in(amenities));
        return cb.exists(subquery);
    }

    static String containsPattern(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        String escaped = value
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }

    private static Predicate withinDistance(Root<Pension> root, CriteriaBuilder cb,
                                            double centerLat, double centerLng, double maxKm) {
        double latDelta = maxKm / 111.32d;
        double cosLat = Math.max(0.01d, Math.abs(Math.cos(Math.toRadians(centerLat))));
        double lngDelta = maxKm / (111.32d * cosLat);

        double south = Math.max(-90d, centerLat - latDelta);
        double north = Math.min(90d, centerLat + latDelta);
        double west = centerLng - lngDelta;
        double east = centerLng + lngDelta;

        Predicate bounds = cb.and(
                cb.isNotNull(root.get("lat")),
                cb.isNotNull(root.get("lng")),
                cb.greaterThanOrEqualTo(root.<Double>get("lat"), south),
                cb.lessThanOrEqualTo(root.<Double>get("lat"), north)
        );

        if (west >= -180d && east <= 180d) {
            bounds = cb.and(bounds,
                    cb.greaterThanOrEqualTo(root.<Double>get("lng"), west),
                    cb.lessThanOrEqualTo(root.<Double>get("lng"), east));
        }

        Expression<Double> distanceKm = distanceExpression(root, cb, centerLat, centerLng);
        return cb.and(bounds, cb.lessThanOrEqualTo(distanceKm, maxKm));
    }

    public static Expression<Double> distanceExpression(Root<Pension> root, CriteriaBuilder cb,
                                                        double centerLat, double centerLng) {
        double earthRadiusKm = 6371.0088d;
        Expression<Double> latRadians = cb.function("radians", Double.class, root.<Double>get("lat"));
        Expression<Double> deltaLngRadians = cb.function(
                "radians", Double.class, cb.diff(root.<Double>get("lng"), centerLng));
        Expression<Double> sinLat = cb.function("sin", Double.class, latRadians);
        Expression<Double> cosLatExpr = cb.function("cos", Double.class, latRadians);
        Expression<Double> cosDeltaLng = cb.function("cos", Double.class, deltaLngRadians);

        Expression<Double> cosine = cb.sum(
                cb.prod(sinLat, Math.sin(Math.toRadians(centerLat))),
                cb.prod(
                        cb.prod(cosLatExpr, Math.cos(Math.toRadians(centerLat))),
                        cosDeltaLng
                )
        );
        Expression<Double> clamped = cb.function(
                "greatest", Double.class, cb.literal(-1d),
                cb.function("least", Double.class, cb.literal(1d), cosine)
        );
        return cb.prod(cb.function("acos", Double.class, clamped), earthRadiusKm);
    }

    public static Specification<Pension> hasCoordinates() {
        return (root, query, cb) -> cb.and(
                cb.isNotNull(root.get("lat")),
                cb.isNotNull(root.get("lng"))
        );
    }

    public enum RoomType { SIMPLE, MATRIMONIAL, ANY }
}
