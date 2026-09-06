package uy.pensiones.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uy.pensiones.enums.BackofficeRole;
import uy.pensiones.model.BackofficeUser;

import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

public interface BackofficeUserRepository extends JpaRepository<BackofficeUser, Long> {
    Optional<BackofficeUser> findByUsernameIgnoreCase(String username);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from BackofficeUser u where u.id = :id")
    Optional<BackofficeUser> findByIdForUpdate(@Param("id") Long id);
    boolean existsByUsernameIgnoreCase(String username);
    long countByRoleAndActiveTrue(BackofficeRole role);
    List<BackofficeUser> findAllByOrderByDisplayNameAscUsernameAsc();
    List<BackofficeUser> findAllBySystemManagedTrue();
}
