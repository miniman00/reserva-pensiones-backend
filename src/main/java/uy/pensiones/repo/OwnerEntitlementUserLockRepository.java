package uy.pensiones.repo;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import uy.pensiones.model.User;

import java.util.Optional;

/** Serializes owner-wide limit-sensitive mutations while monetization enforcement is active. */
public interface OwnerEntitlementUserLockRepository extends Repository<User, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :userId")
    Optional<User> lockUser(@Param("userId") Long userId);
}
