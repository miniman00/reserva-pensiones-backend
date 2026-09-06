package uy.pensiones.repo;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uy.pensiones.model.LaunchCampaign;

import java.util.Optional;

public interface LaunchCampaignRepository extends JpaRepository<LaunchCampaign, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from LaunchCampaign c where c.code = :code")
    Optional<LaunchCampaign> findByCodeForUpdate(@Param("code") String code);

    Optional<LaunchCampaign> findByCode(String code);
}
