package uy.pensiones.repo;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.query.Param;
import uy.pensiones.model.Pension;
import uy.pensiones.enums.PensionStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface PensionRepository extends JpaRepository<Pension, Long> , JpaSpecificationExecutor<Pension> {
    List<Pension> findByOrgId(Long orgId);
    List<Pension> findByOrgIdIn(Iterable<Long> orgIds);
    List<Pension> findByOwnerId(Long ownerId);
    List<Pension> findByCountryCodeIgnoreCase(String countryCode);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE pensions SET draft_step = :step WHERE id = :id AND status = 'DRAFT'", nativeQuery = true)
    int updateDraftStep(@Param("id") Long id, @Param("step") String step);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Pension p where p.id = :id")
    Optional<Pension> findByIdForEntitlementUpdate(@Param("id") Long id);

    @Query(value = """
            SELECT MIN(psc.study_center)
            FROM pension_study_centers psc
            JOIN pensions p ON p.id = psc.pension_id
            JOIN users created_u ON created_u.id = p.created_by_id
            LEFT JOIN users owner_u ON owner_u.id = p.owner_id
            WHERE p.status = 'PUBLISHED'
              AND COALESCE(p.moderation_blocked, false) = false
              AND COALESCE(owner_u.suspended, created_u.suspended, false) = false
              AND p.availability_updated_at >= :availabilityCutoff
              AND (:q = '' OR LOWER(psc.study_center) LIKE CONCAT('%', LOWER(:q), '%'))
            GROUP BY LOWER(psc.study_center)
            ORDER BY MIN(psc.study_center)
            LIMIT :limit
            """, nativeQuery = true)
    List<String> findPublicStudyCenters(@Param("q") String q, @Param("availabilityCutoff") OffsetDateTime availabilityCutoff, @Param("limit") int limit);

    @Query("""
            select p.id as id, p.updatedAt as updatedAt
            from Pension p
            where p.status = :status
              and p.moderationBlocked = false
              and ((p.owner is not null and p.owner.suspended = false)
                   or (p.owner is null and p.createdBy.suspended = false))
              and p.availabilityUpdatedAt >= :availabilityCutoff
            order by p.updatedAt desc
            """)
    List<SitemapEntry> findPublicSitemapEntries(@Param("status") PensionStatus status, @Param("availabilityCutoff") OffsetDateTime availabilityCutoff);

    interface SitemapEntry {
        Long getId();
        OffsetDateTime getUpdatedAt();
    }

    @Query(value = """
            SELECT
                (COALESCE(p.featured_image = :filename, false) OR EXISTS (
                    SELECT 1 FROM pension_media pm
                    WHERE pm.pension_id = p.id AND pm.filename = :filename
                )) AS known_file,
                (p.status = 'PUBLISHED'
                    AND COALESCE(p.moderation_blocked, false) = false
                    AND COALESCE(owner_u.suspended, created_u.suspended, false) = false
                    AND p.availability_updated_at >= :availabilityCutoff
                ) AS public_visible
            FROM pensions p
            JOIN users created_u ON created_u.id = p.created_by_id
            LEFT JOIN users owner_u ON owner_u.id = p.owner_id
            WHERE p.id = :pensionId
            """, nativeQuery = true)
    Optional<MediaAccessState> findMediaAccessState(@Param("pensionId") Long pensionId,
                                                    @Param("filename") String filename,
                                                    @Param("availabilityCutoff") OffsetDateTime availabilityCutoff);

    interface MediaAccessState {
        Boolean getKnownFile();
        Boolean getPublicVisible();
    }

    @EntityGraph(attributePaths = {"owner", "createdBy"})
    Optional<Pension> findWithOwnerById(Long id);

    @EntityGraph(attributePaths = {"owner", "createdBy"})
    @Query("""
            select p from Pension p
            where p.status = :status
              and p.availabilityUpdatedAt is not null
              and p.availabilityUpdatedAt <= :before
            order by p.availabilityUpdatedAt asc
            """)
    List<Pension> findAvailabilityMaintenanceCandidates(@Param("status") PensionStatus status,
                                                        @Param("before") OffsetDateTime before);

    @EntityGraph(attributePaths = {"owner", "createdBy"})
    List<Pension> findByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(PensionStatus status, OffsetDateTime before);

    default List<Pension> findDraftMaintenanceCandidates(PensionStatus status, OffsetDateTime before) {
        return findByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(status, before);
    }

    @Query(value = """
            SELECT EXISTS (
                SELECT 1
                FROM pensions other
                WHERE other.id <> :pensionId
                  AND COALESCE(other.owner_id, other.created_by_id) = :responsibleId
                  AND regexp_replace(lower(trim(COALESCE(other.address_line1, ''))), '[[:space:]]+', ' ', 'g') = :address
                  AND regexp_replace(lower(trim(COALESCE(other.city, ''))), '[[:space:]]+', ' ', 'g') = :city
            )
            """, nativeQuery = true)
    boolean existsPotentialDuplicate(@Param("pensionId") Long pensionId,
                                     @Param("responsibleId") Long responsibleId,
                                     @Param("address") String normalizedAddress,
                                     @Param("city") String normalizedCity);

    interface FeaturedImageReference {
        Long getPensionId();
        String getFilename();
    }

    @Query("select p.id as pensionId, p.featuredImage as filename from Pension p where p.featuredImage is not null")
    List<FeaturedImageReference> findFeaturedImageReferences();
}
