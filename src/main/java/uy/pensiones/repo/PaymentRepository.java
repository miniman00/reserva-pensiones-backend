package uy.pensiones.repo;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import uy.pensiones.enums.PaymentProvider;
import uy.pensiones.enums.PaymentPurpose;
import uy.pensiones.enums.PaymentStatus;
import uy.pensiones.model.PaymentRecord;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<PaymentRecord, Long> {

    @EntityGraph(attributePaths = {"user", "planVersion", "planVersion.plan", "pension", "pension.owner", "pension.createdBy",
            "promotionProductVersion", "promotionProductVersion.product", "studyCenter", "createdByBackoffice", "fulfilledSubscription", "fulfilledPromotion"})
    @Query("select p from PaymentRecord p where p.id = :id")
    Optional<PaymentRecord> findDetailedById(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PaymentRecord p where p.id = :id")
    Optional<PaymentRecord> findByIdForUpdate(@Param("id") Long id);

    @EntityGraph(attributePaths = {"user", "planVersion", "planVersion.plan", "pension", "pension.owner", "pension.createdBy",
            "promotionProductVersion", "promotionProductVersion.product", "studyCenter", "createdByBackoffice", "fulfilledSubscription", "fulfilledPromotion"})
    @Query(value = """
            select p from PaymentRecord p
             where (:q = '' or lower(p.user.name) like lower(concat('%', :q, '%'))
                             or lower(p.user.email) like lower(concat('%', :q, '%'))
                             or lower(p.merchantReference) like lower(concat('%', :q, '%'))
                             or lower(coalesce(p.providerPaymentId, '')) like lower(concat('%', :q, '%'))
                             or lower(coalesce(p.providerCheckoutId, '')) like lower(concat('%', :q, '%')))
               and (:provider is null or p.provider = :provider)
               and (:purpose is null or p.purpose = :purpose)
               and (:status is null or p.status = :status)
            """,
            countQuery = """
            select count(p) from PaymentRecord p
             where (:q = '' or lower(p.user.name) like lower(concat('%', :q, '%'))
                             or lower(p.user.email) like lower(concat('%', :q, '%'))
                             or lower(p.merchantReference) like lower(concat('%', :q, '%'))
                             or lower(coalesce(p.providerPaymentId, '')) like lower(concat('%', :q, '%'))
                             or lower(coalesce(p.providerCheckoutId, '')) like lower(concat('%', :q, '%')))
               and (:provider is null or p.provider = :provider)
               and (:purpose is null or p.purpose = :purpose)
               and (:status is null or p.status = :status)
            """)
    Page<PaymentRecord> adminList(@Param("q") String q,
                                  @Param("provider") PaymentProvider provider,
                                  @Param("purpose") PaymentPurpose purpose,
                                  @Param("status") PaymentStatus status,
                                  Pageable pageable);

    @EntityGraph(attributePaths = {"planVersion", "planVersion.plan", "pension",
            "promotionProductVersion", "promotionProductVersion.product", "studyCenter"})
    @Query("select p from PaymentRecord p where p.id = :id and p.user.id = :userId and p.createdByBackoffice is null")
    Optional<PaymentRecord> findOwnerDetailedById(@Param("id") Long id, @Param("userId") Long userId);

    @EntityGraph(attributePaths = {"planVersion", "planVersion.plan", "pension",
            "promotionProductVersion", "promotionProductVersion.product", "studyCenter"})
    @Query(value = "select p from PaymentRecord p where p.user.id = :userId and p.createdByBackoffice is null order by p.createdAt desc, p.id desc",
            countQuery = "select count(p) from PaymentRecord p where p.user.id = :userId and p.createdByBackoffice is null")
    Page<PaymentRecord> findOwnerPayments(@Param("userId") Long userId, Pageable pageable);

    Optional<PaymentRecord> findByIdempotencyKey(String idempotencyKey);

    Optional<PaymentRecord> findByProviderAndProviderCheckoutId(PaymentProvider provider, String providerCheckoutId);

    Optional<PaymentRecord> findByProviderAndProviderPaymentId(PaymentProvider provider, String providerPaymentId);

    long countByProviderAndProviderCheckoutIdIsNotNull(PaymentProvider provider);

    long countByProviderAndProviderModeAndProviderCheckoutIdIsNotNull(PaymentProvider provider, uy.pensiones.enums.PaymentProviderMode providerMode);

    long countByProviderAndProviderModeAndProviderCheckoutIdIsNotNullAndCreatedAtGreaterThanEqual(
            PaymentProvider provider, uy.pensiones.enums.PaymentProviderMode providerMode, java.time.OffsetDateTime since);

    long countByProviderAndStatusAndFulfilledAtIsNotNull(PaymentProvider provider, PaymentStatus status);

    long countByProviderAndProviderModeAndStatusAndFulfilledAtIsNotNull(PaymentProvider provider,
                                                                         uy.pensiones.enums.PaymentProviderMode providerMode,
                                                                         PaymentStatus status);

    long countByProviderAndProviderModeAndStatusAndFulfilledAtIsNotNullAndCreatedAtGreaterThanEqual(
            PaymentProvider provider, uy.pensiones.enums.PaymentProviderMode providerMode,
            PaymentStatus status, java.time.OffsetDateTime since);

    @Query("select p.id from PaymentRecord p where " +
            "(p.status in :statuses or (:retryMasterDisabled = true and p.status = :approved " +
            "and p.fulfilledAt is null and p.fulfillmentErrorCode = 'PAYMENTS_MASTER_DISABLED')) " +
            "and (p.providerCheckoutId is not null or p.providerPaymentId is not null) " +
            "and coalesce(p.lastReconciliationAttemptAt, p.lastProviderSyncAt, p.createdAt) < :before " +
            "order by coalesce(p.lastReconciliationAttemptAt, p.lastProviderSyncAt, p.createdAt) asc")
    List<Long> findAutomaticReconciliationCandidates(@Param("statuses") Collection<PaymentStatus> statuses,
                                                     @Param("approved") PaymentStatus approved,
                                                     @Param("retryMasterDisabled") boolean retryMasterDisabled,
                                                     @Param("before") OffsetDateTime before, Pageable pageable);

    @Query(value = """
            SELECT COUNT(*) AS "total",
                   COUNT(*) FILTER (WHERE status = 'PENDING') AS "pending",
                   COUNT(*) FILTER (WHERE status = 'APPROVED') AS "approved",
                   COUNT(*) FILTER (WHERE status = 'REJECTED') AS "rejected",
                   COUNT(*) FILTER (WHERE (status = 'APPROVED' AND fulfilled_at IS NULL) OR fulfillment_error_code IS NOT NULL) AS "approvedUnfulfilled",
                   COALESCE(SUM(amount) FILTER (WHERE status = 'APPROVED'), 0) AS "approvedAmount"
              FROM payments
            """, nativeQuery = true)
    PaymentSummaryRow summary();

    interface PaymentSummaryRow {
        Long getTotal();
        Long getPending();
        Long getApproved();
        Long getRejected();
        Long getApprovedUnfulfilled();
        java.math.BigDecimal getApprovedAmount();
    }
}
