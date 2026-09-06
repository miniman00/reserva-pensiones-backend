package uy.pensiones.repo;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uy.pensiones.enums.PlanVersionStatus;
import uy.pensiones.model.PlanVersion;

import java.util.List;
import java.util.Optional;

public interface PlanVersionRepository extends JpaRepository<PlanVersion, Long> {
    List<PlanVersion> findByPlanIdOrderByVersionDesc(Long planId);
    List<PlanVersion> findByPlanIdAndStatusOrderByEffectiveFromAsc(Long planId, PlanVersionStatus status);
    Optional<PlanVersion> findTopByPlanIdOrderByVersionDesc(Long planId);

    @EntityGraph(attributePaths = "plan")
    @Query("""
            select v from PlanVersion v join v.plan p
            where upper(p.code) = upper(:planCode)
              and p.active = true
              and v.status = :status
              and v.effectiveFrom is not null
              and v.effectiveFrom <= :now
              and (v.effectiveUntil is null or v.effectiveUntil > :now)
            order by v.version desc, v.id desc
            """)
    List<PlanVersion> findEffectiveFreeVersions(@Param("planCode") String planCode,
                                                @Param("status") PlanVersionStatus status,
                                                @Param("now") java.time.OffsetDateTime now);

    @EntityGraph(attributePaths = "plan")
    @Query("""
            select v from PlanVersion v join v.plan p
            where p.active = true
              and v.status = :status
              and v.effectiveFrom is not null
              and v.effectiveFrom <= :now
              and (v.effectiveUntil is null or v.effectiveUntil > :now)
            order by p.name asc, v.version desc, v.id desc
            """)
    List<PlanVersion> findEffectivePublishedCatalog(@Param("status") PlanVersionStatus status,
                                                    @Param("now") java.time.OffsetDateTime now);

    @Query("select v.plan.id from PlanVersion v where v.id = :id")
    Optional<Long> findPlanIdByVersionId(@Param("id") Long id);
}
