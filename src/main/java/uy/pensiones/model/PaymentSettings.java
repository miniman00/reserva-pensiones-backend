package uy.pensiones.model;

import jakarta.persistence.*;
import lombok.*;
import uy.pensiones.enums.PaymentAlertSeverity;
import uy.pensiones.enums.PaymentProvider;

import java.time.OffsetDateTime;

@Entity
@Table(name = "payment_settings")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PaymentSettings {
    @Id
    private Short id;

    @Column(name = "payments_enabled", nullable = false)
    private boolean paymentsEnabled;

    @Enumerated(EnumType.STRING)
    @Column(name = "default_provider", length = 40)
    private PaymentProvider defaultProvider;

    @Column(name = "automatic_reconciliation_enabled", nullable = false)
    private boolean automaticReconciliationEnabled;

    @Column(name = "reconciliation_interval_minutes", nullable = false)
    private int reconciliationIntervalMinutes;

    @Column(name = "reconciliation_batch_size", nullable = false)
    private int reconciliationBatchSize;

    @Column(name = "reconciliation_lease_owner", length = 120)
    private String reconciliationLeaseOwner;

    @Column(name = "reconciliation_lease_until")
    private OffsetDateTime reconciliationLeaseUntil;

    @Column(name = "reconciliation_last_started_at")
    private OffsetDateTime reconciliationLastStartedAt;

    @Column(name = "reconciliation_last_completed_at")
    private OffsetDateTime reconciliationLastCompletedAt;

    @Column(name = "reconciliation_last_success")
    private Boolean reconciliationLastSuccess;

    @Column(name = "reconciliation_last_message", length = 500)
    private String reconciliationLastMessage;

    @Column(name = "operational_alert_email_enabled", nullable = false)
    private boolean operationalAlertEmailEnabled;

    @Column(name = "operational_alert_email_recipients", columnDefinition = "text")
    private String operationalAlertEmailRecipients;

    @Enumerated(EnumType.STRING)
    @Column(name = "operational_alert_min_severity", nullable = false, length = 16)
    private PaymentAlertSeverity operationalAlertMinSeverity;

    @Column(name = "operational_alert_cooldown_minutes", nullable = false)
    private int operationalAlertCooldownMinutes;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by_backoffice_user_id")
    private BackofficeUser updatedByBackoffice;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist @PreUpdate
    void touch() { updatedAt = OffsetDateTime.now(); }
}
