package uy.pensiones.repo;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uy.pensiones.enums.PromotionProductVersionStatus;
import uy.pensiones.model.PromotionProductVersion;

import java.util.List;
import java.util.Optional;

public interface PromotionProductVersionRepository extends JpaRepository<PromotionProductVersion, Long> {
    List<PromotionProductVersion> findByProductIdOrderByVersionDesc(Long productId);
    List<PromotionProductVersion> findByProductIdAndStatusOrderByEffectiveFromAsc(
            Long productId, PromotionProductVersionStatus status);
    Optional<PromotionProductVersion> findTopByProductIdOrderByVersionDesc(Long productId);

    @EntityGraph(attributePaths = "product")
    @Query("""
            select v from PromotionProductVersion v join v.product p
            where p.active = true
              and v.status = :status
              and v.effectiveFrom is not null
              and v.effectiveFrom <= :now
              and (v.effectiveUntil is null or v.effectiveUntil > :now)
            order by p.targetType asc, p.durationDays asc, p.name asc, v.version desc, v.id desc
            """)
    List<PromotionProductVersion> findEffectivePublishedCatalog(
            @Param("status") PromotionProductVersionStatus status,
            @Param("now") java.time.OffsetDateTime now);

    @Query("select v.product.id from PromotionProductVersion v where v.id = :id")
    Optional<Long> findProductIdByVersionId(@Param("id") Long id);
}
