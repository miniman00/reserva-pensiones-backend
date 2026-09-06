package uy.pensiones.repo;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import uy.pensiones.model.User;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Consultas agregadas exclusivas del Backoffice. Mantenerlas fuera de UserRepository
 * evita cargar al repositorio del marketplace con joins y métricas administrativas.
 */
public interface AdminUserQueryRepository extends Repository<User, Long> {

    @Query(value = """
            SELECT COUNT(*) AS "total",
                   COUNT(*) FILTER (WHERE role = 'OWNER') AS "owners",
                   COUNT(*) FILTER (WHERE COALESCE(suspended, false) = true) AS "suspended",
                   COUNT(*) FILTER (WHERE COALESCE(email_verified, false) = true) AS "verified"
            FROM users
            WHERE role IN ('SEEKER', 'OWNER')
            """, nativeQuery = true)
    AdminUserSummaryRow findSummary();

    @Query(value = """
            SELECT
                (SELECT COUNT(*) FROM organizations o WHERE o.owner_id = :userId) AS "organizationCount",
                (SELECT COUNT(*) FROM memberships m WHERE m.user_id = :userId AND m.status = 'ACTIVE') AS "activeMembershipCount",
                (SELECT COUNT(*) FROM pensions p WHERE COALESCE(p.owner_id, p.created_by_id) = :userId) AS "responsiblePensionCount",
                (SELECT COUNT(*) FROM pensions p WHERE COALESCE(p.owner_id, p.created_by_id) = :userId AND p.status = 'PUBLISHED') AS "publishedPensionCount",
                (SELECT COUNT(*) FROM pensions p WHERE COALESCE(p.owner_id, p.created_by_id) = :userId AND p.status = 'PAUSED') AS "pausedPensionCount",
                (SELECT COUNT(*) FROM pensions p WHERE COALESCE(p.owner_id, p.created_by_id) = :userId AND p.status = 'DRAFT') AS "draftPensionCount",
                (SELECT COUNT(*) FROM pensions p WHERE COALESCE(p.owner_id, p.created_by_id) = :userId AND COALESCE(p.moderation_blocked, false) = true) AS "blockedPensionCount",
                (SELECT COUNT(*) FROM pensions p WHERE COALESCE(p.owner_id, p.created_by_id) = :userId AND p.status = 'PUBLISHED' AND COALESCE(p.moderation_blocked, false) = false) AS "publicVisibilityPensionCount",
                (SELECT COUNT(*) FROM pension_members pm JOIN pensions p ON p.id = pm.pension_id WHERE pm.user_id = :userId AND COALESCE(p.owner_id, p.created_by_id) <> :userId) AS "collaborationCount",
                (SELECT COUNT(*) FROM pension_inquiries i WHERE i.requester_id = :userId) AS "sentInquiryCount",
                (SELECT COUNT(*) FROM pension_favorites f WHERE f.user_id = :userId) AS "favoriteCount",
                (SELECT COUNT(*) FROM pension_reports r WHERE r.reporter_user_id = :userId) AS "submittedReportCount",
                (SELECT COUNT(*) FROM pension_reports r JOIN pensions p ON p.id = r.pension_id WHERE COALESCE(p.owner_id, p.created_by_id) = :userId) AS "receivedReportCount",
                (SELECT COUNT(*) FROM pension_reports r JOIN pensions p ON p.id = r.pension_id WHERE COALESCE(p.owner_id, p.created_by_id) = :userId AND r.status IN ('NEW', 'UNDER_REVIEW')) AS "openReceivedReportCount",
                (SELECT COUNT(*) FROM pension_inquiries i JOIN pensions p ON p.id = i.pension_id WHERE COALESCE(p.owner_id, p.created_by_id) = :userId) AS "receivedInquiryCount",
                (SELECT COUNT(*) FROM pension_views v JOIN pensions p ON p.id = v.pension_id WHERE COALESCE(p.owner_id, p.created_by_id) = :userId) AS "receivedViewCount",
                (SELECT COUNT(*) FROM pension_favorites f JOIN pensions p ON p.id = f.pension_id WHERE COALESCE(p.owner_id, p.created_by_id) = :userId) AS "receivedFavoriteCount"
            """, nativeQuery = true)
    AdminUserActivityRow findActivity(@Param("userId") Long userId);

    @Query(value = """
            SELECT p.id AS "id",
                   p.name AS "name",
                   o.name AS "organizationName",
                   p.city AS "city",
                   p.state AS "state",
                   p.status AS "status",
                   COALESCE(p.moderation_blocked, false) AS "moderationBlocked",
                   COALESCE(p.featured, false) AS "featured",
                   NULL::varchar AS "relationshipRole",
                   (SELECT COUNT(*) FROM pension_reports r WHERE r.pension_id = p.id) AS "reportCount",
                   (SELECT COUNT(*) FROM pension_reports r WHERE r.pension_id = p.id AND r.status IN ('NEW', 'UNDER_REVIEW')) AS "openReportCount",
                   p.updated_at AS "updatedAt"
            FROM pensions p
            JOIN organizations o ON o.id = p.org_id
            WHERE COALESCE(p.owner_id, p.created_by_id) = :userId
            ORDER BY p.updated_at DESC, p.id DESC
            LIMIT 20
            """, nativeQuery = true)
    List<AdminUserPensionRow> findRecentResponsiblePensions(@Param("userId") Long userId);

    @Query(value = """
            SELECT p.id AS "id",
                   p.name AS "name",
                   o.name AS "organizationName",
                   p.city AS "city",
                   p.state AS "state",
                   p.status AS "status",
                   COALESCE(p.moderation_blocked, false) AS "moderationBlocked",
                   COALESCE(p.featured, false) AS "featured",
                   pm.role AS "relationshipRole",
                   (SELECT COUNT(*) FROM pension_reports r WHERE r.pension_id = p.id) AS "reportCount",
                   (SELECT COUNT(*) FROM pension_reports r WHERE r.pension_id = p.id AND r.status IN ('NEW', 'UNDER_REVIEW')) AS "openReportCount",
                   p.updated_at AS "updatedAt"
            FROM pension_members pm
            JOIN pensions p ON p.id = pm.pension_id
            JOIN organizations o ON o.id = p.org_id
            WHERE pm.user_id = :userId
              AND COALESCE(p.owner_id, p.created_by_id) <> :userId
            ORDER BY p.updated_at DESC, p.id DESC
            LIMIT 20
            """, nativeQuery = true)
    List<AdminUserPensionRow> findRecentCollaboratingPensions(@Param("userId") Long userId);

    interface AdminUserSummaryRow {
        Long getTotal();
        Long getOwners();
        Long getSuspended();
        Long getVerified();
    }

    interface AdminUserActivityRow {
        Long getOrganizationCount();
        Long getActiveMembershipCount();
        Long getResponsiblePensionCount();
        Long getPublishedPensionCount();
        Long getPausedPensionCount();
        Long getDraftPensionCount();
        Long getBlockedPensionCount();
        Long getPublicVisibilityPensionCount();
        Long getCollaborationCount();
        Long getSentInquiryCount();
        Long getFavoriteCount();
        Long getSubmittedReportCount();
        Long getReceivedReportCount();
        Long getOpenReceivedReportCount();
        Long getReceivedInquiryCount();
        Long getReceivedViewCount();
        Long getReceivedFavoriteCount();
    }

    interface AdminUserPensionRow {
        Long getId();
        String getName();
        String getOrganizationName();
        String getCity();
        String getState();
        String getStatus();
        Boolean getModerationBlocked();
        Boolean getFeatured();
        String getRelationshipRole();
        Long getReportCount();
        Long getOpenReportCount();
        OffsetDateTime getUpdatedAt();
    }
}
