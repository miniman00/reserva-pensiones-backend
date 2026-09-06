package uy.pensiones.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import uy.pensiones.model.MockPaymentState;

public interface MockPaymentStateRepository extends JpaRepository<MockPaymentState, String> {
    java.util.Optional<MockPaymentState> findByIdempotencyKey(String idempotencyKey);
}
