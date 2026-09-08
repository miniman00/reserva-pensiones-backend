package uy.pensiones.repo;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uy.pensiones.enums.LaunchCampaignBeneficiaryStatus;
import uy.pensiones.model.LaunchCampaignBeneficiary;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface LaunchCampaignBeneficiaryRepository extends JpaRepository<LaunchCampaignBeneficiary, Long> {
    boolean existsByCampaignIdAndUserId(Long campaignId, Long userId);
    Optional<LaunchCampaignBeneficiary> findByCampaignIdAndUserId(Long campaignId, Long userId);
    long countByCampaignId(Long campaignId);
    long countByCampaignIdAndStatus(Long campaignId, LaunchCampaignBeneficiaryStatus status);
    long countByCampaignIdAndStatusAndExpiresAtAfter(Long campaignId, LaunchCampaignBeneficiaryStatus status, OffsetDateTime after);
    long countByCampaignIdAndStatusAndExpiresAtBetween(Long campaignId, LaunchCampaignBeneficiaryStatus status,
                                                        OffsetDateTime from, OffsetDateTime to);

    @EntityGraph(attributePaths = {"campaign", "user", "sourcePension", "planVersion", "planVersion.plan", "grantedByBackofficeUser"})
    @Query("""
            select b from LaunchCampaignBeneficiary b
             where b.campaign.id = :campaignId
               and (:status is null or b.status = :status)
               and (:q = ''
                    or lower(coalesce(b.user.name, '')) like lower(concat('%', :q, '%'))
                    or lower(b.user.email) like lower(concat('%', :q, '%'))
                    or lower(coalesce(b.sourcePension.name, '')) like lower(concat('%', :q, '%')))
            """)
    Page<LaunchCampaignBeneficiary> searchAdmin(@Param("campaignId") Long campaignId,
                                                 @Param("q") String q,
                                                 @Param("status") LaunchCampaignBeneficiaryStatus status,
                                                 Pageable pageable);

    @EntityGraph(attributePaths = {"campaign", "user", "sourcePension", "planVersion", "planVersion.plan", "grantedByBackofficeUser"})
    @Query("select b from LaunchCampaignBeneficiary b where b.id = :id")
    Optional<LaunchCampaignBeneficiary> findAdminDetailById(@Param("id") Long id);

    @EntityGraph(attributePaths = {"campaign", "user", "sourcePension", "planVersion", "planVersion.plan"})
    @Query("""
            select b from LaunchCampaignBeneficiary b
             where b.campaign.code = :campaignCode
               and b.status = :status
               and b.expiresAt <= :until
             order by b.expiresAt asc, b.id asc
            """)
    List<LaunchCampaignBeneficiary> findLifecycleCandidates(@Param("campaignCode") String campaignCode,
                                                             @Param("status") LaunchCampaignBeneficiaryStatus status,
                                                             @Param("until") OffsetDateTime until);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = {"campaign", "user", "sourcePension", "planVersion", "planVersion.plan"})
    @Query("select b from LaunchCampaignBeneficiary b where b.id = :id")
    Optional<LaunchCampaignBeneficiary> findByIdForUpdate(@Param("id") Long id);

    @EntityGraph(attributePaths = {"campaign", "planVersion", "planVersion.plan"})
    @Query("""
            select b from LaunchCampaignBeneficiary b
             where b.user.id = :userId
               and b.campaign.code = :campaignCode
               and b.status = :status
               and b.grantedAt <= :now
               and b.expiresAt > :now
            """)
    Optional<LaunchCampaignBeneficiary> findEffectiveDetailed(@Param("userId") Long userId,
                                                               @Param("campaignCode") String campaignCode,
                                                               @Param("status") LaunchCampaignBeneficiaryStatus status,
                                                               @Param("now") OffsetDateTime now);

    @EntityGraph(attributePaths = {"campaign", "planVersion", "planVersion.plan", "user", "sourcePension"})
    @Query("""
            select b from LaunchCampaignBeneficiary b
             where b.user.id = :userId
               and b.campaign.code = :campaignCode
             order by b.grantedAt desc, b.id desc
            """)
    List<LaunchCampaignBeneficiary> findHistoryDetailed(@Param("userId") Long userId,
                                                         @Param("campaignCode") String campaignCode);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select b from LaunchCampaignBeneficiary b
             where b.user.id = :userId
               and b.campaign.code = :campaignCode
               and b.status = :status
               and b.grantedAt <= :now
               and b.expiresAt > :now
            """)
    Optional<LaunchCampaignBeneficiary> findEffectiveForUpdate(@Param("userId") Long userId,
                                                                @Param("campaignCode") String campaignCode,
                                                                @Param("status") LaunchCampaignBeneficiaryStatus status,
                                                                @Param("now") OffsetDateTime now);
}
