package uy.pensiones.repo;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import uy.pensiones.model.User;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/** Read-only usage queries used by commercial entitlement resolution and enforcement. */
public interface OwnerEntitlementQueryRepository extends Repository<User, Long> {

    @Query(value = """
            SELECT COUNT(*)
              FROM pensions p
             WHERE COALESCE(p.owner_id, p.created_by_id) = :userId
            """, nativeQuery = true)
    long countResponsiblePensions(@Param("userId") Long userId);

    @Query(value = """
            SELECT COALESCE(p.owner_id, p.created_by_id)
              FROM pensions p
             WHERE p.id = :pensionId
            """, nativeQuery = true)
    Optional<Long> findResponsibleUserId(@Param("pensionId") Long pensionId);

    @Query(value = """
            SELECT (SELECT COUNT(*) FROM pension_members pm WHERE pm.pension_id = :pensionId)
                 + (SELECT COUNT(DISTINCT LOWER(pi.email)) FROM pension_invites pi
                      WHERE pi.pension_id = :pensionId
                        AND pi.status = 'PENDING'
                        AND pi.expires_at > :now
                        AND NOT EXISTS (
                            SELECT 1
                              FROM users u
                              JOIN pension_members pm ON pm.user_id = u.id AND pm.pension_id = :pensionId
                             WHERE LOWER(u.email) = LOWER(pi.email)
                        ))
            """, nativeQuery = true)
    long countCollaboratorSlots(@Param("pensionId") Long pensionId, @Param("now") OffsetDateTime now);

    @Query(value = """
            SELECT COUNT(*) FROM pension_media m
             WHERE m.pension_id = :pensionId AND m.kind = 'IMAGE'
            """, nativeQuery = true)
    long countPhotos(@Param("pensionId") Long pensionId);

    @Query(value = """
            SELECT COUNT(*) FROM pension_media m
             WHERE m.pension_id = :pensionId AND m.kind IN ('VIDEO', 'YOUTUBE')
            """, nativeQuery = true)
    long countVideos(@Param("pensionId") Long pensionId);

    @Query(value = """
            SELECT p.id AS "pensionId",
                   p.name AS "pensionName",
                   (SELECT COUNT(*) FROM pension_members pm WHERE pm.pension_id = p.id) AS "memberCount",
                   (SELECT COUNT(DISTINCT LOWER(pi.email)) FROM pension_invites pi
                      WHERE pi.pension_id = p.id
                        AND pi.status = 'PENDING'
                        AND pi.expires_at > :now
                        AND NOT EXISTS (
                            SELECT 1
                              FROM users u
                              JOIN pension_members pm2 ON pm2.user_id = u.id AND pm2.pension_id = p.id
                             WHERE LOWER(u.email) = LOWER(pi.email)
                        )) AS "pendingInviteCount",
                   (SELECT COUNT(*) FROM pension_media m WHERE m.pension_id = p.id AND m.kind = 'IMAGE') AS "photoCount",
                   (SELECT COUNT(*) FROM pension_media m WHERE m.pension_id = p.id AND m.kind IN ('VIDEO', 'YOUTUBE')) AS "videoCount"
              FROM pensions p
             WHERE COALESCE(p.owner_id, p.created_by_id) = :userId
             ORDER BY p.updated_at DESC, p.id DESC
             LIMIT 100
            """, nativeQuery = true)
    List<PensionUsageRow> findPensionUsage(@Param("userId") Long userId, @Param("now") OffsetDateTime now);

    interface PensionUsageRow {
        Long getPensionId();
        String getPensionName();
        Long getMemberCount();
        Long getPendingInviteCount();
        Long getPhotoCount();
        Long getVideoCount();
    }
}
