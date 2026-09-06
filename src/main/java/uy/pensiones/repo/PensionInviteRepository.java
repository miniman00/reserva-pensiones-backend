package uy.pensiones.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import uy.pensiones.enums.InviteStatus;
import uy.pensiones.model.PensionInvite;

import java.util.List;
import java.util.Optional;

public interface PensionInviteRepository extends JpaRepository<PensionInvite, Long> {
    @EntityGraph(attributePaths = "pension")
    Optional<PensionInvite> findByToken(String token);
    Optional<PensionInvite> findByIdAndPensionId(Long id, Long pensionId);
    List<PensionInvite> findByPensionIdAndStatus(Long pensionId, InviteStatus status);
    Optional<PensionInvite> findFirstByPensionIdAndEmailAndStatus(Long pensionId, String email, InviteStatus status);
}