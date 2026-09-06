package uy.pensiones.repo;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uy.pensiones.model.PensionFavorite;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PensionFavoriteRepository extends JpaRepository<PensionFavorite, Long> {

    @EntityGraph(attributePaths = {"pension", "pension.owner", "pension.createdBy"})
    List<PensionFavorite> findByUserIdOrderByCreatedAtDesc(Long userId);

    Optional<PensionFavorite> findByUserIdAndPensionId(Long userId, Long pensionId);

    boolean existsByUserIdAndPensionId(Long userId, Long pensionId);

    void deleteByUserIdAndPensionId(Long userId, Long pensionId);

    long countByUserId(Long userId);

    @Query("""
            select f.pension.id as pensionId, count(f.id) as total
            from PensionFavorite f
            where f.pension.id in :pensionIds
            group by f.pension.id
            """)
    List<PensionFavoriteCount> countByPensionIds(@Param("pensionIds") Collection<Long> pensionIds);


    @Query("""
            select f.pension.id as pensionId, count(f.id) as total
            from PensionFavorite f
            where f.pension.id in :pensionIds
              and f.createdAt >= :since
            group by f.pension.id
            """)
    List<PensionFavoriteCount> countByPensionIdsSince(@Param("pensionIds") Collection<Long> pensionIds,
                                                      @Param("since") OffsetDateTime since);

    interface PensionFavoriteCount {
        Long getPensionId();
        long getTotal();
    }
}
