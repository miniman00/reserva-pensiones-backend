package uy.pensiones.repo;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import uy.pensiones.model.StudyCenterCatalog;

import java.time.Instant;
import java.util.List;

/** Consultas agregadas exclusivas del Backoffice para el catálogo de centros. */
public interface AdminStudyCenterQueryRepository extends Repository<StudyCenterCatalog, Long> {

    @Query(value = """
            SELECT sc.id AS "id",
                   sc.name AS "name",
                   sc.city AS "city",
                   sc.country_code AS "countryCode",
                   sc.lat AS "lat",
                   sc.lng AS "lng",
                   COALESCE(sc.verified, false) AS "verified",
                   COALESCE(sc.active, true) AS "active",
                   sc.created_at AS "createdAt",
                   sc.updated_at AS "updatedAt",
                   COUNT(DISTINCT psc.pension_id) AS "usageCount",
                   COUNT(DISTINCT p.id) FILTER (WHERE p.status = 'PUBLISHED') AS "publishedPensionCount",
                   COUNT(DISTINCT p.id) FILTER (
                       WHERE p.status = 'PUBLISHED'
                         AND COALESCE(p.moderation_blocked, false) = false
                         AND COALESCE(owner_user.suspended, false) = false
                   ) AS "visiblePensionCount"
            FROM study_center_catalog sc
            LEFT JOIN pension_study_centers psc
              ON LOWER(TRANSLATE(TRIM(REGEXP_REPLACE(psc.study_center, '[[:space:]]+', ' ', 'g')), 'ÁÉÍÓÚÜÑáéíóúüñ', 'AEIOUUNaeiouun')) = sc.normalized_name
            LEFT JOIN pensions p ON p.id = psc.pension_id
            LEFT JOIN users owner_user ON owner_user.id = COALESCE(p.owner_id, p.created_by_id)
            WHERE (:q = '' OR LOWER(sc.name) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(COALESCE(sc.city, '')) LIKE LOWER(CONCAT('%', :q, '%')))
              AND (:active IS NULL OR sc.active = :active)
              AND (:verified IS NULL OR sc.verified = :verified)
              AND (:geolocated IS NULL
                   OR (:geolocated = true AND sc.lat IS NOT NULL AND sc.lng IS NOT NULL)
                   OR (:geolocated = false AND (sc.lat IS NULL OR sc.lng IS NULL)))
            GROUP BY sc.id
            ORDER BY sc.name ASC, sc.id ASC
            """,
            countQuery = """
            SELECT COUNT(*)
            FROM study_center_catalog sc
            WHERE (:q = '' OR LOWER(sc.name) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(COALESCE(sc.city, '')) LIKE LOWER(CONCAT('%', :q, '%')))
              AND (:active IS NULL OR sc.active = :active)
              AND (:verified IS NULL OR sc.verified = :verified)
              AND (:geolocated IS NULL
                   OR (:geolocated = true AND sc.lat IS NOT NULL AND sc.lng IS NOT NULL)
                   OR (:geolocated = false AND (sc.lat IS NULL OR sc.lng IS NULL)))
            """,
            nativeQuery = true)
    Page<AdminStudyCenterRow> search(@Param("q") String q,
                                     @Param("active") Boolean active,
                                     @Param("verified") Boolean verified,
                                     @Param("geolocated") Boolean geolocated,
                                     Pageable pageable);

    @Query(value = """
            SELECT COUNT(*) AS "total",
                   COUNT(*) FILTER (WHERE active = true) AS "active",
                   COUNT(*) FILTER (WHERE active = false) AS "inactive",
                   COUNT(*) FILTER (WHERE verified = true) AS "verified",
                   COUNT(*) FILTER (WHERE verified = false) AS "pending",
                   COUNT(*) FILTER (WHERE lat IS NULL OR lng IS NULL) AS "missingCoordinates",
                   COUNT(*) FILTER (WHERE EXISTS (
                       SELECT 1 FROM pension_study_centers psc
                       WHERE LOWER(TRANSLATE(TRIM(REGEXP_REPLACE(psc.study_center, '[[:space:]]+', ' ', 'g')), 'ÁÉÍÓÚÜÑáéíóúüñ', 'AEIOUUNaeiouun')) = study_center_catalog.normalized_name
                   )) AS "inUse"
            FROM study_center_catalog
            """, nativeQuery = true)
    AdminStudyCenterSummaryRow summary();

    @Query(value = """
            SELECT p.id AS "id",
                   p.name AS "name",
                   p.city AS "city",
                   p.state AS "state",
                   p.status AS "status",
                   COALESCE(p.moderation_blocked, false) AS "moderationBlocked",
                   COALESCE(p.featured, false) AS "featured",
                   owner_user.id AS "ownerId",
                   owner_user.name AS "ownerName",
                   owner_user.email AS "ownerEmail",
                   COALESCE(owner_user.suspended, false) AS "ownerSuspended",
                   p.updated_at AS "updatedAt"
            FROM pension_study_centers psc
            JOIN pensions p ON p.id = psc.pension_id
            LEFT JOIN users owner_user ON owner_user.id = COALESCE(p.owner_id, p.created_by_id)
            WHERE LOWER(TRANSLATE(TRIM(REGEXP_REPLACE(psc.study_center, '[[:space:]]+', ' ', 'g')), 'ÁÉÍÓÚÜÑáéíóúüñ', 'AEIOUUNaeiouun')) = :normalizedName
            ORDER BY p.updated_at DESC, p.id DESC
            LIMIT 20
            """, nativeQuery = true)
    List<AdminStudyCenterPensionRow> recentLinkedPensions(@Param("normalizedName") String normalizedName);

    @Query(value = """
            SELECT COUNT(*) FILTER (WHERE distance_km <= 1.0) AS "within1Km",
                   COUNT(*) FILTER (WHERE distance_km <= 3.0) AS "within3Km",
                   COUNT(*) FILTER (WHERE distance_km <= 5.0) AS "within5Km"
            FROM (
                SELECT 6371.0088 * ACOS(GREATEST(-1.0, LEAST(1.0,
                    SIN(RADIANS(:lat)) * SIN(RADIANS(p.lat))
                    + COS(RADIANS(:lat)) * COS(RADIANS(p.lat)) * COS(RADIANS(p.lng - :lng))
                ))) AS distance_km
                FROM pensions p
                LEFT JOIN users owner_user ON owner_user.id = COALESCE(p.owner_id, p.created_by_id)
                WHERE p.lat IS NOT NULL AND p.lng IS NOT NULL
                  AND p.status = 'PUBLISHED'
                  AND COALESCE(p.moderation_blocked, false) = false
                  AND COALESCE(owner_user.suspended, false) = false
            ) visible_pensions
            """, nativeQuery = true)
    AdminStudyCenterDistanceRow distanceMetrics(@Param("lat") Double lat, @Param("lng") Double lng);

    interface AdminStudyCenterRow {
        Long getId();
        String getName();
        String getCity();
        String getCountryCode();
        Double getLat();
        Double getLng();
        Boolean getVerified();
        Boolean getActive();
        Instant getCreatedAt();
        Instant getUpdatedAt();
        Long getUsageCount();
        Long getPublishedPensionCount();
        Long getVisiblePensionCount();
    }

    interface AdminStudyCenterSummaryRow {
        Long getTotal();
        Long getActive();
        Long getInactive();
        Long getVerified();
        Long getPending();
        Long getMissingCoordinates();
        Long getInUse();
    }

    interface AdminStudyCenterPensionRow {
        Long getId();
        String getName();
        String getCity();
        String getState();
        String getStatus();
        Boolean getModerationBlocked();
        Boolean getFeatured();
        Long getOwnerId();
        String getOwnerName();
        String getOwnerEmail();
        Boolean getOwnerSuspended();
        Instant getUpdatedAt();
    }

    interface AdminStudyCenterDistanceRow {
        Long getWithin1Km();
        Long getWithin3Km();
        Long getWithin5Km();
    }
}
