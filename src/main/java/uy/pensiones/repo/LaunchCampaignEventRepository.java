package uy.pensiones.repo;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import uy.pensiones.model.LaunchCampaignEvent;

public interface LaunchCampaignEventRepository extends JpaRepository<LaunchCampaignEvent, Long> {
    @EntityGraph(attributePaths = {"beneficiary", "user", "pension", "backofficeUser"})
    Page<LaunchCampaignEvent> findByCampaignIdOrderByCreatedAtDescIdDesc(Long campaignId, Pageable pageable);
}
