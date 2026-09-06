package uy.pensiones.repo;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import uy.pensiones.enums.PaymentRefundStatus;
import uy.pensiones.model.PaymentRefund;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PaymentRefundRepository extends JpaRepository<PaymentRefund, Long> {
    @EntityGraph(attributePaths = {"requestedByBackoffice"})
    List<PaymentRefund> findByPayment_IdOrderByCreatedAtDescIdDesc(Long paymentId);

    Optional<PaymentRefund> findByIdempotencyKey(String idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from PaymentRefund r where r.idempotencyKey = :key")
    Optional<PaymentRefund> findByIdempotencyKeyForUpdate(@Param("key") String key);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from PaymentRefund r where r.id = :id")
    Optional<PaymentRefund> findByIdForUpdate(@Param("id") Long id);

    @Query("select coalesce(sum(r.requestedAmount), 0) from PaymentRefund r where r.payment.id = :paymentId and r.status = :status")
    BigDecimal sumAmountByPaymentAndStatus(@Param("paymentId") Long paymentId, @Param("status") PaymentRefundStatus status);

    boolean existsByPayment_IdAndStatusIn(Long paymentId, java.util.Collection<PaymentRefundStatus> statuses);

    long countByProviderAndStatus(uy.pensiones.enums.PaymentProvider provider, PaymentRefundStatus status);

    long countByPayment_ProviderAndPayment_ProviderModeAndStatus(uy.pensiones.enums.PaymentProvider provider,
                                                                  uy.pensiones.enums.PaymentProviderMode providerMode,
                                                                  PaymentRefundStatus status);

    long countByPayment_ProviderAndPayment_ProviderModeAndStatusAndCreatedAtGreaterThanEqual(
            uy.pensiones.enums.PaymentProvider provider, uy.pensiones.enums.PaymentProviderMode providerMode,
            PaymentRefundStatus status, java.time.OffsetDateTime since);

    @Query("select r.id from PaymentRefund r where r.status in :statuses " +
            "and coalesce(r.lastReconciliationAttemptAt, r.updatedAt) < :before " +
            "order by coalesce(r.lastReconciliationAttemptAt, r.updatedAt) asc")
    List<Long> findAutomaticReconciliationCandidates(@Param("statuses") Collection<PaymentRefundStatus> statuses,
                                                     @Param("before") OffsetDateTime before, Pageable pageable);
}
