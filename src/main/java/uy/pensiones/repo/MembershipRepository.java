package uy.pensiones.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import uy.pensiones.model.Membership;

import java.util.List;
import java.util.Optional;

public interface MembershipRepository extends JpaRepository<Membership, Long> {
    List<Membership> findByUserIdAndStatus(Long userId, Membership.MembershipStatus status);
    List<Membership> findByOrgId(Long orgId);
    Optional<Membership> findByOrgIdAndUserId(Long orgId, Long userId);
}
