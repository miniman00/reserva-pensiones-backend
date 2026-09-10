package uy.pensiones.model;

import jakarta.persistence.*;
import lombok.*;
import uy.pensiones.enums.PaymentProvider;
import uy.pensiones.enums.PaymentProviderMode;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;

@Entity
@Table(name = "payment_provider_environment_checks")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@IdClass(PaymentProviderEnvironmentCheck.Key.class)
public class PaymentProviderEnvironmentCheck {
    @Id
    @Enumerated(EnumType.STRING)
    @Column(length = 40)
    private PaymentProvider provider;

    @Id
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private PaymentProviderMode mode;

    @Column(name = "credential_fingerprint", length = 64)
    private String credentialFingerprint;

    @Column(name = "checked_at", nullable = false)
    private OffsetDateTime checkedAt;

    @Column(nullable = false)
    private boolean success;

    @Column(length = 500)
    private String message;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "checked_by_backoffice_user_id")
    private BackofficeUser checkedByBackoffice;

    @PrePersist
    void prePersist() {
        if (checkedAt == null) checkedAt = OffsetDateTime.now();
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    public static class Key implements Serializable {
        private PaymentProvider provider;
        private PaymentProviderMode mode;

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key key)) return false;
            return provider == key.provider && mode == key.mode;
        }

        @Override public int hashCode() { return Objects.hash(provider, mode); }
    }
}
