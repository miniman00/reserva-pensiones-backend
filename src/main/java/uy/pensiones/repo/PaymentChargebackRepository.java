package uy.pensiones.repo;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import uy.pensiones.enums.PaymentChargebackStatus;
import uy.pensiones.enums.PaymentProvider;
import uy.pensiones.model.PaymentChargeback;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface PaymentChargebackRepository extends JpaRepository<PaymentChargeback, Long> {

    long countByProvider(PaymentProvider provider);

    long countByProviderAndLiveMode(PaymentProvider provider, Boolean liveMode);

    long countByProviderAndLiveModeAndCreatedAtGreaterThanEqual(PaymentProvider provider, Boolean liveMode,
                                                                 OffsetDateTime since);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from PaymentChargeback c where c.provider=:provider and c.providerChargebackId=:providerChargebackId")
    Optional<PaymentChargeback> findByProviderAndProviderChargebackIdForUpdate(@Param("provider") PaymentProvider provider,
                                                                                @Param("providerChargebackId") String providerChargebackId);

    @EntityGraph(attributePaths = {"payment", "payment.user", "payment.planVersion", "payment.planVersion.plan", "payment.pension", "payment.fulfilledSubscription", "payment.fulfilledPromotion", "benefitDecidedByBackoffice", "documentationSubmittedByBackoffice"})
    @Query("select c from PaymentChargeback c where c.id=:id")
    Optional<PaymentChargeback> findDetailedById(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from PaymentChargeback c where c.id=:id")
    Optional<PaymentChargeback> findByIdForUpdate(@Param("id") Long id);

    @EntityGraph(attributePaths = {"payment", "payment.user", "payment.planVersion", "payment.planVersion.plan", "payment.pension", "benefitDecidedByBackoffice", "documentationSubmittedByBackoffice"})
    @Query(value = """
            select c from PaymentChargeback c
             left join c.payment p
             left join p.user u
             where (:q='' or lower(c.providerChargebackId) like lower(concat('%',:q,'%'))
                            or lower(coalesce(c.providerPaymentId,'')) like lower(concat('%',:q,'%'))
                            or lower(coalesce(u.name,'')) like lower(concat('%',:q,'%'))
                            or lower(coalesce(u.email,'')) like lower(concat('%',:q,'%')))
               and (:status is null or c.status=:status)
               and (:documentationRequired is null or c.documentationRequired=:documentationRequired)
               and (:reviewRequired is null or
                    (:reviewRequired=true and c.status=:lostStatus and c.payment is not null and p.fulfilledAt is not null and c.benefitDecision is null)
                    or (:reviewRequired=false and (c.status<>:lostStatus or c.payment is null or p.fulfilledAt is null or c.benefitDecision is not null)))
            """,
            countQuery = """
            select count(c) from PaymentChargeback c
             left join c.payment p
             left join p.user u
             where (:q='' or lower(c.providerChargebackId) like lower(concat('%',:q,'%'))
                            or lower(coalesce(c.providerPaymentId,'')) like lower(concat('%',:q,'%'))
                            or lower(coalesce(u.name,'')) like lower(concat('%',:q,'%'))
                            or lower(coalesce(u.email,'')) like lower(concat('%',:q,'%')))
               and (:status is null or c.status=:status)
               and (:documentationRequired is null or c.documentationRequired=:documentationRequired)
               and (:reviewRequired is null or
                    (:reviewRequired=true and c.status=:lostStatus and c.payment is not null and p.fulfilledAt is not null and c.benefitDecision is null)
                    or (:reviewRequired=false and (c.status<>:lostStatus or c.payment is null or p.fulfilledAt is null or c.benefitDecision is not null)))
            """)
    Page<PaymentChargeback> adminList(@Param("q") String q,
                                      @Param("status") PaymentChargebackStatus status,
                                      @Param("documentationRequired") Boolean documentationRequired,
                                      @Param("reviewRequired") Boolean reviewRequired,
                                      @Param("lostStatus") PaymentChargebackStatus lostStatus,
                                      Pageable pageable);

    @Query(value = """
            SELECT COUNT(*) AS "total",
                   COUNT(*) FILTER (WHERE status='OPEN') AS "open",
                   COUNT(*) FILTER (WHERE status='WON') AS "won",
                   COUNT(*) FILTER (WHERE status='LOST') AS "lost",
                   COUNT(*) FILTER (WHERE documentation_required = TRUE AND status='OPEN' AND lower(coalesce(documentation_status,''))='not_supplied' AND documentation_deadline > CURRENT_TIMESTAMP) AS "documentationRequired",
                   COUNT(*) FILTER (WHERE payment_id IS NULL) AS "unmatched",
                   COUNT(*) FILTER (WHERE status='LOST' AND payment_id IS NOT NULL AND benefit_decision IS NULL
                        AND EXISTS (SELECT 1 FROM payments p WHERE p.id=payment_id AND p.fulfilled_at IS NOT NULL)) AS "benefitReviewRequired"
              FROM payment_chargebacks
            """, nativeQuery = true)
    ChargebackSummaryRow summary();

    interface ChargebackSummaryRow {
        Long getTotal(); Long getOpen(); Long getWon(); Long getLost(); Long getDocumentationRequired(); Long getUnmatched(); Long getBenefitReviewRequired();
    }
    @Query("select c.id from PaymentChargeback c where c.status = :status " +
            "and coalesce(c.lastReconciliationAttemptAt, c.lastSyncedAt, c.createdAt) < :before " +
            "order by coalesce(c.lastReconciliationAttemptAt, c.lastSyncedAt, c.createdAt) asc")
    List<Long> findAutomaticReconciliationCandidates(@Param("status") PaymentChargebackStatus status,
                                                     @Param("before") OffsetDateTime before, Pageable pageable);

}
