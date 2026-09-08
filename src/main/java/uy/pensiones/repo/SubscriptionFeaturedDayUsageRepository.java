package uy.pensiones.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uy.pensiones.model.SubscriptionFeaturedDayUsage;

import java.time.OffsetDateTime;
import java.util.List;

public interface SubscriptionFeaturedDayUsageRepository extends JpaRepository<SubscriptionFeaturedDayUsage, Long> {

    @Query("""
            select coalesce(sum(u.days), 0)
              from SubscriptionFeaturedDayUsage u
             where u.subscription.id = :subscriptionId
               and u.cycleStart = :cycleStart
            """)
    Long sumDaysForCycle(@Param("subscriptionId") Long subscriptionId,
                         @Param("cycleStart") OffsetDateTime cycleStart);

    List<SubscriptionFeaturedDayUsage> findBySubscription_Id(Long subscriptionId);
}
