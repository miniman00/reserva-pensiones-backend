package uy.pensiones.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import uy.pensiones.enums.UserRole;
import uy.pensiones.model.User;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {
  Optional<User> findByProviderAndProviderId(String provider, String providerId);
  Optional<User> findByEmail(String email);
  long countByRole(UserRole role);
  long countBySuspendedTrue();
  long countByEmailVerifiedTrue();
}
