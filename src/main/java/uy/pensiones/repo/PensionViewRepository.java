package uy.pensiones.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import uy.pensiones.model.PensionView;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

public interface PensionViewRepository extends JpaRepository<PensionView, Long> {

    @Modifying
    @Transactional
    @Query(value = """
            insert into pension_views (pension_id, visitor_hash, viewed_on, created_at)
            values (:pensionId, :visitorHash, :viewedOn, :createdAt)
            on conflict (pension_id, visitor_hash, viewed_on) do nothing
            """, nativeQuery = true)
    int insertIgnore(@Param("pensionId") Long pensionId,
                     @Param("visitorHash") String visitorHash,
                     @Param("viewedOn") LocalDate viewedOn,
                     @Param("createdAt") OffsetDateTime createdAt);

    @Query("""
            select v.pension.id as pensionId, count(v.id) as total
            from PensionView v
            where v.pension.id in :pensionIds
            group by v.pension.id
            """)
    List<PensionViewCount> countByPensionIds(@Param("pensionIds") Collection<Long> pensionIds);

    @Query("""
            select v.pension.id as pensionId, count(v.id) as total
            from PensionView v
            where v.pension.id in :pensionIds
              and v.viewedOn >= :fromDate
            group by v.pension.id
            """)
    List<PensionViewCount> countByPensionIdsSince(@Param("pensionIds") Collection<Long> pensionIds,
                                                  @Param("fromDate") LocalDate fromDate);

    interface PensionViewCount {
        Long getPensionId();
        long getTotal();
    }
}
