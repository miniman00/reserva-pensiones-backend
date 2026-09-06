package uy.pensiones.repo;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uy.pensiones.model.PaymentProviderEvent;
import uy.pensiones.enums.PaymentProvider;

import java.util.Optional;

public interface PaymentProviderEventRepository extends JpaRepository<PaymentProviderEvent, Long>, JpaSpecificationExecutor<PaymentProviderEvent> {
    Optional<PaymentProviderEvent> findByProviderAndProviderEventId(PaymentProvider provider, String providerEventId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from PaymentProviderEvent e where e.id = :id")
    Optional<PaymentProviderEvent> findByIdForUpdate(@Param("id") Long id);

    long countByProcessedAtIsNotNull();
    long countByProviderAndProcessedAtIsNotNull(PaymentProvider provider);
    long countByProviderAndProcessedAtIsNotNullAndLiveMode(PaymentProvider provider, Boolean liveMode);
    long countByProviderAndProcessedAtIsNotNullAndLiveModeAndCreatedAtGreaterThanEqual(PaymentProvider provider,
                                                                                        Boolean liveMode,
                                                                                        java.time.OffsetDateTime since);
    long countByProcessedAtIsNullAndProcessingErrorIsNotNull();
    long countByManualReplayInProgressTrue();
}
