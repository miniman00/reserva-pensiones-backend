package uy.pensiones.repo;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uy.pensiones.enums.PaymentProvider;
import uy.pensiones.enums.PaymentProviderCertificationStatus;
import uy.pensiones.model.PaymentProviderCertificationRun;

import java.util.Optional;

public interface PaymentProviderCertificationRunRepository extends JpaRepository<PaymentProviderCertificationRun, Long> {
    Optional<PaymentProviderCertificationRun> findFirstByProviderAndStatusOrderByStartedAtDesc(
            PaymentProvider provider, PaymentProviderCertificationStatus status);

    Optional<PaymentProviderCertificationRun> findFirstByProviderOrderByStartedAtDesc(PaymentProvider provider);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from PaymentProviderCertificationRun r where r.id=:id")
    Optional<PaymentProviderCertificationRun> findByIdForUpdate(@Param("id") Long id);
}
