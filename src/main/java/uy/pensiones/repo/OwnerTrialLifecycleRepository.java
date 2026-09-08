package uy.pensiones.repo;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uy.pensiones.enums.OwnerTrialConsumptionReason;
import uy.pensiones.model.OwnerTrialLifecycle;

import java.util.List;
import java.util.Optional;

public interface OwnerTrialLifecycleRepository extends JpaRepository<OwnerTrialLifecycle, Long> {

    @EntityGraph(attributePaths = {"user", "sourcePension", "trialPlanVersion", "trialPlanVersion.plan"})
    Optional<OwnerTrialLifecycle> findByUserId(Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = {"user", "sourcePension", "trialPlanVersion", "trialPlanVersion.plan"})
    @Query("select l from OwnerTrialLifecycle l where l.user.id = :userId")
    Optional<OwnerTrialLifecycle> findByUserIdForUpdate(@Param("userId") Long userId);

    @EntityGraph(attributePaths = {"user", "trialPlanVersion", "trialPlanVersion.plan"})
    List<OwnerTrialLifecycle> findByAccessSuspendedAtIsNullOrderByConsumedAtAsc();

    long countByConsumptionReason(OwnerTrialConsumptionReason reason);
}
