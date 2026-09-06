package uy.pensiones.repo;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uy.pensiones.model.PromotionProduct;

import java.util.List;
import java.util.Optional;

public interface PromotionProductRepository extends JpaRepository<PromotionProduct, Long> {
    Optional<PromotionProduct> findByCodeIgnoreCase(String code);
    List<PromotionProduct> findAllByOrderByTargetTypeAscDurationDaysAscNameAsc();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PromotionProduct p where p.id = :id")
    Optional<PromotionProduct> findByIdForUpdate(@Param("id") Long id);
}
