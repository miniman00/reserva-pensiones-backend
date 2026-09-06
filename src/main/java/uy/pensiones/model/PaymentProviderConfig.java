package uy.pensiones.model;

import jakarta.persistence.*;
import lombok.*;
import uy.pensiones.enums.PaymentProvider;
import uy.pensiones.enums.PaymentProviderMode;

import java.time.OffsetDateTime;

@Entity
@Table(name = "payment_provider_configs")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PaymentProviderConfig {
    @Id
    @Enumerated(EnumType.STRING)
    @Column(length = 40)
    private PaymentProvider provider;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Column(nullable = false)
    private boolean enabled;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentProviderMode mode;

    @Column(nullable = false)
    private int priority;

    @Column(name = "subscriptions_enabled", nullable = false)
    private boolean subscriptionsEnabled;

    @Column(name = "promotions_enabled", nullable = false)
    private boolean promotionsEnabled;

    @Column(name = "supported_currencies", length = 500)
    private String supportedCurrencies;

    @Column(name = "supported_countries", length = 1000)
    private String supportedCountries;

    @Column(name = "configuration_json", columnDefinition = "text")
    private String configurationJson;

    @Column(name = "last_connectivity_check_at")
    private OffsetDateTime lastConnectivityCheckAt;

    @Column(name = "last_connectivity_check_success")
    private Boolean lastConnectivityCheckSuccess;

    @Column(name = "last_connectivity_check_message", length = 500)
    private String lastConnectivityCheckMessage;

    @Column(name = "last_webhook_at")
    private OffsetDateTime lastWebhookAt;

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
        if (mode == null) mode = PaymentProviderMode.TEST;
    }

    @PreUpdate
    void preUpdate() { updatedAt = OffsetDateTime.now(); }
}
