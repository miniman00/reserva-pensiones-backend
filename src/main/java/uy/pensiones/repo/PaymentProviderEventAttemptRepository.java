package uy.pensiones.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import uy.pensiones.enums.PaymentProviderEventAttemptStatus;
import uy.pensiones.model.PaymentProviderEventAttempt;

import java.util.List;

public interface PaymentProviderEventAttemptRepository extends JpaRepository<PaymentProviderEventAttempt, Long> {
    List<PaymentProviderEventAttempt> findByEventIdOrderByStartedAtDescIdDesc(Long eventId);
    List<PaymentProviderEventAttempt> findByEventIdAndStatus(Long eventId, PaymentProviderEventAttemptStatus status);
}
