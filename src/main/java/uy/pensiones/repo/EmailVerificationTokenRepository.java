package uy.pensiones.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import uy.pensiones.model.EmailVerificationToken;

import java.util.Optional;

public interface EmailVerificationTokenRepository extends JpaRepository<EmailVerificationToken, Long> {
    Optional<EmailVerificationToken> findTopByUserIdAndStatusOrderByCreatedAtDesc(Long userId, EmailVerificationToken.Status status);
}
