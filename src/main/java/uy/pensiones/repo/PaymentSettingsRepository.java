package uy.pensiones.repo;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import uy.pensiones.model.PaymentSettings;

import java.util.Optional;

public interface PaymentSettingsRepository extends JpaRepository<PaymentSettings, Short> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from PaymentSettings s where s.id = :id")
    Optional<PaymentSettings> findByIdForUpdate(@Param("id") Short id);
}
