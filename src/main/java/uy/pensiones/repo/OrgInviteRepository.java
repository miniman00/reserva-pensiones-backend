package uy.pensiones.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import uy.pensiones.enums.InviteStatus;
import uy.pensiones.model.OrgInvite;

import java.util.Optional;

public interface OrgInviteRepository extends JpaRepository<OrgInvite, Long> {
    Optional<OrgInvite> findByEmailAndStatus(String email, InviteStatus status);
}
