package uy.pensiones.repo;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import uy.pensiones.model.Pension;

/**
 * Métricas agregadas exclusivas del Dashboard del Backoffice.
 * No devuelve PII y puede ser consultado por cualquier rol interno autenticado.
 */
public interface AdminDashboardQueryRepository extends Repository<Pension, Long> {

    @Query(value = """
            SELECT
                (SELECT COUNT(*) FROM users u WHERE u.role IN ('SEEKER', 'OWNER')) AS "marketplaceUsers",
                (SELECT COUNT(*) FROM users u WHERE u.role = 'OWNER') AS "ownerRoleUsers",
                (SELECT COUNT(DISTINCT COALESCE(p.owner_id, p.created_by_id)) FROM pensions p) AS "pensionOwners",
                (SELECT COUNT(*) FROM users u WHERE u.role IN ('SEEKER', 'OWNER') AND COALESCE(u.suspended, false) = true) AS "suspendedUsers",

                (SELECT COUNT(*) FROM pensions p) AS "pensions",
                (SELECT COUNT(*) FROM pensions p WHERE p.status = 'DRAFT') AS "draftPensions",
                (SELECT COUNT(*) FROM pensions p WHERE p.status = 'PUBLISHED') AS "publishedPensions",
                (SELECT COUNT(*) FROM pensions p WHERE p.status = 'PAUSED') AS "pausedPensions",
                (SELECT COUNT(*) FROM pensions p WHERE COALESCE(p.moderation_blocked, false) = true) AS "blockedPensions",
                (SELECT COUNT(*) FROM pensions p WHERE COALESCE(p.featured, false) = true) AS "featuredPensions",
                (SELECT COUNT(*)
                   FROM pensions p
                   JOIN users owner_u ON owner_u.id = COALESCE(p.owner_id, p.created_by_id)
                  WHERE p.status = 'PUBLISHED'
                    AND COALESCE(p.moderation_blocked, false) = false
                    AND COALESCE(owner_u.suspended, false) = false) AS "visiblePensions",
                (SELECT COUNT(*)
                   FROM pensions p
                   JOIN users owner_u ON owner_u.id = COALESCE(p.owner_id, p.created_by_id)
                  WHERE p.status = 'PUBLISHED'
                    AND COALESCE(p.moderation_blocked, false) = false
                    AND COALESCE(owner_u.suspended, false) = false
                    AND COALESCE(p.available_simple, 0) + COALESCE(p.available_matrimonial, 0) > 0) AS "visibleAvailablePensions",

                (SELECT COUNT(*) FROM pension_reports r) AS "reports",
                (SELECT COUNT(*) FROM pension_reports r WHERE r.status = 'NEW') AS "newReports",
                (SELECT COUNT(*) FROM pension_reports r WHERE r.status = 'UNDER_REVIEW') AS "underReviewReports",

                (SELECT COUNT(*) FROM pension_inquiries i) AS "inquiries",
                (SELECT COUNT(*) FROM pension_inquiries i WHERE i.status = 'NEW') AS "newInquiries",
                (SELECT COUNT(*) FROM pension_views v) AS "views",
                (SELECT COUNT(*) FROM pension_favorites f) AS "favorites",

                (SELECT COUNT(*) FROM study_center_catalog sc) AS "studyCenters",
                (SELECT COUNT(*) FROM study_center_catalog sc WHERE COALESCE(sc.active, true) = true) AS "activeStudyCenters",
                (SELECT COUNT(*) FROM study_center_catalog sc WHERE COALESCE(sc.verified, false) = true) AS "verifiedStudyCenters",
                (SELECT COUNT(*) FROM study_center_catalog sc WHERE sc.lat IS NULL OR sc.lng IS NULL) AS "studyCentersMissingCoordinates",

                (SELECT COUNT(*) FROM owner_subscriptions s
                  WHERE s.status = 'ACTIVE'
                    AND s.started_at <= CURRENT_TIMESTAMP
                    AND s.expires_at > CURRENT_TIMESTAMP) AS "activeSubscriptions",
                (SELECT COUNT(*) FROM owner_subscriptions s WHERE s.source = 'ADMIN_GRANT') AS "adminGrantedSubscriptions",
                (SELECT COUNT(*) FROM pension_promotions pp
                  WHERE pp.status <> 'CANCELLED'
                    AND pp.starts_at <= CURRENT_TIMESTAMP
                    AND (pp.ends_at IS NULL OR pp.ends_at > CURRENT_TIMESTAMP)) AS "activePromotions",
                (SELECT COUNT(*) FROM pension_promotions pp WHERE pp.source = 'ADMIN_GRANT') AS "adminGrantedPromotions"
            """, nativeQuery = true)
    AdminDashboardMetrics metrics();

    interface AdminDashboardMetrics {
        Long getMarketplaceUsers();
        Long getOwnerRoleUsers();
        Long getPensionOwners();
        Long getSuspendedUsers();
        Long getPensions();
        Long getDraftPensions();
        Long getPublishedPensions();
        Long getPausedPensions();
        Long getBlockedPensions();
        Long getFeaturedPensions();
        Long getVisiblePensions();
        Long getVisibleAvailablePensions();
        Long getReports();
        Long getNewReports();
        Long getUnderReviewReports();
        Long getInquiries();
        Long getNewInquiries();
        Long getViews();
        Long getFavorites();
        Long getStudyCenters();
        Long getActiveStudyCenters();
        Long getVerifiedStudyCenters();
        Long getStudyCentersMissingCoordinates();
        Long getActiveSubscriptions();
        Long getAdminGrantedSubscriptions();
        Long getActivePromotions();
        Long getAdminGrantedPromotions();
    }
}
