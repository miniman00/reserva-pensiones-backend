package uy.pensiones.repo;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uy.pensiones.enums.PaymentProviderCertificationCheckCode;
import uy.pensiones.model.PaymentProviderCertificationCheck;

import java.util.List;
import java.util.Optional;

public interface PaymentProviderCertificationCheckRepository extends JpaRepository<PaymentProviderCertificationCheck, Long> {
    List<PaymentProviderCertificationCheck> findByRun_IdOrderByCheckCodeAsc(Long runId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from PaymentProviderCertificationCheck c where c.run.id=:runId and c.checkCode=:code")
    Optional<PaymentProviderCertificationCheck> findForUpdate(@Param("runId") Long runId,
                                                               @Param("code") PaymentProviderCertificationCheckCode code);
}
