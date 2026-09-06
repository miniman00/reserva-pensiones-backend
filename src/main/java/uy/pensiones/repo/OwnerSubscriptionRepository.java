package uy.pensiones.repo;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.repository.query.Param;
import uy.pensiones.enums.SubscriptionStatus;
import uy.pensiones.model.OwnerSubscription;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface OwnerSubscriptionRepository extends JpaRepository<OwnerSubscription, Long>, JpaSpecificationExecutor<OwnerSubscription> {

    @Override
    @EntityGraph(attributePaths = {"user", "planVersion", "planVersion.plan"})
    Page<OwnerSubscription> findAll(Specification<OwnerSubscription> spec, Pageable pageable);

    @EntityGraph(attributePaths = {"user", "planVersion", "planVersion.plan"})
    @Query("select s from OwnerSubscription s where s.id = :id")
    Optional<OwnerSubscription> findDetailedById(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from OwnerSubscription s where s.id = :id")
    Optional<OwnerSubscription> findByIdForUpdate(@Param("id") Long id);

    @Query("select s.user.id from OwnerSubscription s where s.id = :id")
    Optional<Long> findUserIdById(@Param("id") Long id);

    List<OwnerSubscription> findByUserIdAndStatusOrderByExpiresAtDesc(Long userId, SubscriptionStatus status);

    @EntityGraph(attributePaths = {"user", "planVersion", "planVersion.plan"})
    @Query("""
            select s from OwnerSubscription s
            where s.user.id = :userId
              and s.status = :status
              and s.startedAt <= :now
              and s.expiresAt > :now
            order by s.startedAt desc, s.id desc
            """)
    List<OwnerSubscription> findEffectiveActiveDetailed(@Param("userId") Long userId,
                                                        @Param("now") OffsetDateTime now,
                                                        @Param("status") SubscriptionStatus status);

    @Query(value = """
            SELECT COUNT(*) AS "total",
                   COUNT(*) FILTER (WHERE status = 'ACTIVE' AND started_at <= :now AND expires_at > :now) AS "active",
                   COUNT(*) FILTER (WHERE status = 'EXPIRED' OR (status = 'ACTIVE' AND expires_at <= :now)) AS "expired",
                   COUNT(*) FILTER (WHERE status = 'CANCELLED') AS "cancelled",
                   COUNT(*) FILTER (WHERE source = 'ADMIN_GRANT') AS "adminGrants"
              FROM owner_subscriptions
            """, nativeQuery = true)
    SubscriptionSummaryRow summary(@Param("now") OffsetDateTime now);

    interface SubscriptionSummaryRow {
        Long getTotal();
        Long getActive();
        Long getExpired();
        Long getCancelled();
        Long getAdminGrants();
    }
}
