package uy.pensiones.repo;

import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import uy.pensiones.model.PaymentReconciliationRun;

import java.util.List;
import java.util.Optional;

public interface PaymentReconciliationRunRepository extends JpaRepository<PaymentReconciliationRun, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from PaymentReconciliationRun r where r.id=:id")
    Optional<PaymentReconciliationRun> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from PaymentReconciliationRun r where r.leaseOwner=:owner and r.status=:status")
    Optional<PaymentReconciliationRun> findByLeaseOwnerAndStatusForUpdate(@Param("owner") String owner,
                                                                          @Param("status") uy.pensiones.enums.PaymentReconciliationRunStatus status);

    @EntityGraph(attributePaths = {"startedByBackoffice"})
    List<PaymentReconciliationRun> findAllByOrderByStartedAtDescIdDesc(Pageable pageable);
}
