package uy.pensiones.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import uy.pensiones.enums.PaymentProvider;
import uy.pensiones.model.PaymentProviderCredential;

import java.util.List;
import java.util.Optional;

public interface PaymentProviderCredentialRepository extends JpaRepository<PaymentProviderCredential, PaymentProviderCredential.Key> {
    List<PaymentProviderCredential> findByProviderOrderByCredentialNameAsc(PaymentProvider provider);
    Optional<PaymentProviderCredential> findByProviderAndCredentialName(PaymentProvider provider, String credentialName);
    void deleteByProviderAndCredentialName(PaymentProvider provider, String credentialName);
}
