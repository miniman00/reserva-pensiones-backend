package uy.pensiones.model;

import jakarta.persistence.*;
import lombok.*;
import uy.pensiones.enums.PaymentProvider;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;

@Entity
@Table(name = "payment_provider_credentials")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@IdClass(PaymentProviderCredential.Key.class)
public class PaymentProviderCredential {
    @Id
    @Enumerated(EnumType.STRING)
    @Column(length = 40)
    private PaymentProvider provider;

    @Id
    @Column(name = "credential_name", length = 80)
    private String credentialName;

    @Column(name = "encrypted_value", nullable = false, columnDefinition = "text")
    private String encryptedValue;

    @Column(nullable = false, length = 64)
    private String fingerprint;

    @Column(name = "masked_suffix", length = 12)
    private String maskedSuffix;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by_backoffice_user_id")
    private BackofficeUser updatedByBackoffice;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate void preUpdate() { updatedAt = OffsetDateTime.now(); }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    public static class Key implements Serializable {
        private PaymentProvider provider;
        private String credentialName;
        @Override public boolean equals(Object o) { if (this == o) return true; if (!(o instanceof Key k)) return false; return provider == k.provider && Objects.equals(credentialName, k.credentialName); }
        @Override public int hashCode() { return Objects.hash(provider, credentialName); }
    }
}
