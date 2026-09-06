package uy.pensiones.repo;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import uy.pensiones.enums.PaymentProvider;
import uy.pensiones.model.PaymentProviderConfig;

import java.util.List;
import java.util.Optional;

public interface PaymentProviderConfigRepository extends JpaRepository<PaymentProviderConfig, PaymentProvider> {
    List<PaymentProviderConfig> findAllByOrderByPriorityAscProviderAsc();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PaymentProviderConfig p where p.provider = :provider")
    Optional<PaymentProviderConfig> findByProviderForUpdate(@Param("provider") PaymentProvider provider);
}
