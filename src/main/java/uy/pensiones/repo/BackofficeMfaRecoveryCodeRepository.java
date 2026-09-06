package uy.pensiones.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import uy.pensiones.model.BackofficeMfaRecoveryCode;

import java.util.List;
import java.util.Optional;

public interface BackofficeMfaRecoveryCodeRepository extends JpaRepository<BackofficeMfaRecoveryCode, Long> {
    List<BackofficeMfaRecoveryCode> findAllByBackofficeUser_IdAndUsedAtIsNull(Long userId);
    Optional<BackofficeMfaRecoveryCode> findByBackofficeUser_IdAndCodeHashAndUsedAtIsNull(Long userId, String codeHash);
    long deleteByBackofficeUser_Id(Long userId);
    long countByBackofficeUser_IdAndUsedAtIsNull(Long userId);
}
