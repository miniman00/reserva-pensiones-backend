package uy.pensiones.repo;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uy.pensiones.model.OwnerTrialSettings;

import java.util.Optional;

public interface OwnerTrialSettingsRepository extends JpaRepository<OwnerTrialSettings, Short> {

    @EntityGraph(attributePaths = {"trialPlanVersion", "trialPlanVersion.plan"})
    @Query("select s from OwnerTrialSettings s where s.id = :id")
    Optional<OwnerTrialSettings> findDetailedById(@Param("id") Short id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = {"trialPlanVersion", "trialPlanVersion.plan"})
    @Query("select s from OwnerTrialSettings s where s.id = :id")
    Optional<OwnerTrialSettings> findByIdForUpdate(@Param("id") Short id);
}
