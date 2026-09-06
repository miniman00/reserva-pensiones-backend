package uy.pensiones.repo;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import uy.pensiones.model.Pension;

import java.time.Instant;
import java.time.OffsetDateTime;

/**
 * Consultas de lectura optimizadas para el Backoffice.
 * Mantiene las proyecciones administrativas fuera del repositorio usado por el portal público.
 */
public interface AdminPensionQueryRepository extends Repository<Pension, Long> {

    @Query(value = """
            SELECT p.id AS "id",
                   p.name AS "name",
                   o.name AS "organizationName",
                   p.city AS "city",
                   p.state AS "state",
                   p.status AS "status",
                   COALESCE(p.moderation_blocked, false) AS "moderationBlocked",
                   COALESCE(p.featured, false) AS "featured",
                   COALESCE(owner_u.id, created_u.id) AS "ownerId",
                   COALESCE(NULLIF(owner_u.name, ''), NULLIF(created_u.name, ''),
                            COALESCE(owner_u.email, created_u.email)) AS "ownerName",
                   COALESCE(owner_u.email, created_u.email) AS "ownerEmail",
                   COALESCE(owner_u.suspended, created_u.suspended, false) AS "ownerSuspended",
                   COALESCE(p.capacity_simple, 0) + COALESCE(p.capacity_matrimonial, 0) AS "capacity",
                   COALESCE(p.available_simple, 0) + COALESCE(p.available_matrimonial, 0) AS "available",
                   p.availability_updated_at AS "availabilityUpdatedAt",
                   p.created_at AS "createdAt",
                   p.updated_at AS "updatedAt",
                   (SELECT COUNT(*) FROM pension_reports r WHERE r.pension_id = p.id) AS "reportCount",
                   (SELECT COUNT(*) FROM pension_reports r
                    WHERE r.pension_id = p.id AND r.status IN ('NEW', 'UNDER_REVIEW')) AS "openReportCount",
                   (SELECT COUNT(*) FROM pension_views v WHERE v.pension_id = p.id) AS "viewCount",
                   (SELECT COUNT(*) FROM pension_inquiries i WHERE i.pension_id = p.id) AS "inquiryCount"
            FROM pensions p
            JOIN organizations o ON o.id = p.org_id
            JOIN users created_u ON created_u.id = p.created_by_id
            LEFT JOIN users owner_u ON owner_u.id = p.owner_id
            WHERE (:q = '' OR LOWER(p.name) LIKE LOWER(CONCAT('%', :q, '%')))
              AND (:owner = '' OR LOWER(COALESCE(owner_u.name, created_u.name, '')) LIKE LOWER(CONCAT('%', :owner, '%')))
              AND (:email = '' OR LOWER(COALESCE(owner_u.email, created_u.email, '')) LIKE LOWER(CONCAT('%', :email, '%')))
              AND (:city = '' OR LOWER(COALESCE(p.city, '')) LIKE LOWER(CONCAT('%', :city, '%')))
              AND (:state = '' OR LOWER(COALESCE(p.state, '')) LIKE LOWER(CONCAT('%', :state, '%')))
              AND (:status = '' OR p.status = :status)
              AND (:moderationBlocked IS NULL OR COALESCE(p.moderation_blocked, false) = :moderationBlocked)
              AND (:featured IS NULL OR COALESCE(p.featured, false) = :featured)
              AND (:available IS NULL OR
                   (:available = true AND COALESCE(p.available_simple, 0) + COALESCE(p.available_matrimonial, 0) > 0) OR
                   (:available = false AND COALESCE(p.available_simple, 0) + COALESCE(p.available_matrimonial, 0) = 0))
              AND (CAST(:createdFrom AS timestamptz) IS NULL OR p.created_at >= CAST(:createdFrom AS timestamptz))
              AND (CAST(:createdToExclusive AS timestamptz) IS NULL OR p.created_at < CAST(:createdToExclusive AS timestamptz))
            ORDER BY p.updated_at DESC, p.id DESC
            """,
            countQuery = """
            SELECT COUNT(*)
            FROM pensions p
            JOIN organizations o ON o.id = p.org_id
            JOIN users created_u ON created_u.id = p.created_by_id
            LEFT JOIN users owner_u ON owner_u.id = p.owner_id
            WHERE (:q = '' OR LOWER(p.name) LIKE LOWER(CONCAT('%', :q, '%')))
              AND (:owner = '' OR LOWER(COALESCE(owner_u.name, created_u.name, '')) LIKE LOWER(CONCAT('%', :owner, '%')))
              AND (:email = '' OR LOWER(COALESCE(owner_u.email, created_u.email, '')) LIKE LOWER(CONCAT('%', :email, '%')))
              AND (:city = '' OR LOWER(COALESCE(p.city, '')) LIKE LOWER(CONCAT('%', :city, '%')))
              AND (:state = '' OR LOWER(COALESCE(p.state, '')) LIKE LOWER(CONCAT('%', :state, '%')))
              AND (:status = '' OR p.status = :status)
              AND (:moderationBlocked IS NULL OR COALESCE(p.moderation_blocked, false) = :moderationBlocked)
              AND (:featured IS NULL OR COALESCE(p.featured, false) = :featured)
              AND (:available IS NULL OR
                   (:available = true AND COALESCE(p.available_simple, 0) + COALESCE(p.available_matrimonial, 0) > 0) OR
                   (:available = false AND COALESCE(p.available_simple, 0) + COALESCE(p.available_matrimonial, 0) = 0))
              AND (CAST(:createdFrom AS timestamptz) IS NULL OR p.created_at >= CAST(:createdFrom AS timestamptz))
              AND (CAST(:createdToExclusive AS timestamptz) IS NULL OR p.created_at < CAST(:createdToExclusive AS timestamptz))
            """, nativeQuery = true)
    Page<AdminPensionRow> search(@Param("q") String q,
                                 @Param("owner") String owner,
                                 @Param("email") String email,
                                 @Param("city") String city,
                                 @Param("state") String state,
                                 @Param("status") String status,
                                 @Param("moderationBlocked") Boolean moderationBlocked,
                                 @Param("featured") Boolean featured,
                                 @Param("available") Boolean available,
                                 @Param("createdFrom") OffsetDateTime createdFrom,
                                 @Param("createdToExclusive") OffsetDateTime createdToExclusive,
                                 Pageable pageable);

    @Query(value = """
            SELECT (SELECT COUNT(*) FROM pension_reports r WHERE r.pension_id = :pensionId) AS "reportCount",
                   (SELECT COUNT(*) FROM pension_reports r
                    WHERE r.pension_id = :pensionId AND r.status IN ('NEW', 'UNDER_REVIEW')) AS "openReportCount",
                   (SELECT COUNT(*) FROM pension_views v WHERE v.pension_id = :pensionId) AS "viewCount",
                   (SELECT COUNT(*) FROM pension_inquiries i WHERE i.pension_id = :pensionId) AS "inquiryCount",
                   (SELECT COUNT(*) FROM pension_favorites f WHERE f.pension_id = :pensionId) AS "favoriteCount"
            """, nativeQuery = true)
    AdminPensionMetrics findMetrics(@Param("pensionId") Long pensionId);

    interface AdminPensionMetrics {
        Long getReportCount();
        Long getOpenReportCount();
        Long getViewCount();
        Long getInquiryCount();
        Long getFavoriteCount();
    }

    interface AdminPensionRow {
        Long getId();
        String getName();
        String getOrganizationName();
        String getCity();
        String getState();
        String getStatus();
        Boolean getModerationBlocked();
        Boolean getFeatured();
        Long getOwnerId();
        String getOwnerName();
        String getOwnerEmail();
        Boolean getOwnerSuspended();
        Integer getCapacity();
        Integer getAvailable();
        Instant getAvailabilityUpdatedAt();
        Instant getCreatedAt();
        Instant getUpdatedAt();
        Long getReportCount();
        Long getOpenReportCount();
        Long getViewCount();
        Long getInquiryCount();
    }
}
