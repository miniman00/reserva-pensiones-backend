package uy.pensiones.repo;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

@Repository
public class OwnerAnalyticsQueryRepository {
    @PersistenceContext
    private EntityManager entityManager;

    public List<Object[]> views(Collection<Long> pensionIds, LocalDate from, LocalDate to) {
        if (pensionIds == null || pensionIds.isEmpty()) return List.of();
        return entityManager.createNativeQuery("""
                SELECT pension_id, viewed_on, COUNT(*)
                FROM pension_views
                WHERE pension_id IN (:ids) AND viewed_on BETWEEN :from AND :to
                GROUP BY pension_id, viewed_on
                """)
                .setParameter("ids", pensionIds).setParameter("from", from).setParameter("to", to)
                .getResultList();
    }

    public List<Object[]> favorites(Collection<Long> pensionIds, LocalDate from, LocalDate to) {
        if (pensionIds == null || pensionIds.isEmpty()) return List.of();
        return entityManager.createNativeQuery("""
                SELECT pension_id, (created_at AT TIME ZONE 'UTC')::date AS day, COUNT(*)
                FROM pension_favorites
                WHERE pension_id IN (:ids)
                  AND created_at >= CAST(:from AS date)
                  AND created_at < CAST(:to AS date) + INTERVAL '1 day'
                GROUP BY pension_id, day
                """)
                .setParameter("ids", pensionIds).setParameter("from", from).setParameter("to", to)
                .getResultList();
    }

    public List<Object[]> inquiries(Collection<Long> pensionIds, LocalDate from, LocalDate to) {
        if (pensionIds == null || pensionIds.isEmpty()) return List.of();
        return entityManager.createNativeQuery("""
                SELECT pension_id, (created_at AT TIME ZONE 'UTC')::date AS day,
                       COUNT(*), COUNT(*) FILTER (WHERE converted_at IS NOT NULL)
                FROM pension_inquiries
                WHERE pension_id IN (:ids)
                  AND created_at >= CAST(:from AS date)
                  AND created_at < CAST(:to AS date) + INTERVAL '1 day'
                GROUP BY pension_id, day
                """)
                .setParameter("ids", pensionIds).setParameter("from", from).setParameter("to", to)
                .getResultList();
    }
}
