package uy.pensiones.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import uy.pensiones.enums.PaymentProvider;
import uy.pensiones.enums.PaymentProviderMode;
import uy.pensiones.model.PaymentProviderEnvironmentCheck;

import java.util.List;
import java.util.Optional;

public interface PaymentProviderEnvironmentCheckRepository
        extends JpaRepository<PaymentProviderEnvironmentCheck, PaymentProviderEnvironmentCheck.Key> {
    Optional<PaymentProviderEnvironmentCheck> findByProviderAndMode(PaymentProvider provider, PaymentProviderMode mode);
    List<PaymentProviderEnvironmentCheck> findByProviderOrderByModeAsc(PaymentProvider provider);
}
