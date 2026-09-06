package uy.pensiones.repo;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import uy.pensiones.model.BackofficeSession;

import java.time.OffsetDateTime;
import java.util.Optional;

public interface BackofficeSessionRepository extends JpaRepository<BackofficeSession, Long> {

    @EntityGraph(attributePaths = "backofficeUser")
    Optional<BackofficeSession> findByTokenHash(String tokenHash);

    long deleteByBackofficeUser_Id(Long userId);

    long deleteByExpiresAtBefore(OffsetDateTime cutoff);
}
