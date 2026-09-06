package uy.pensiones.model;

import jakarta.persistence.*;
import lombok.*;
import uy.pensiones.enums.ChargebackBenefitDecision;
import uy.pensiones.enums.ChargebackDocumentationSubmissionState;
import uy.pensiones.enums.PaymentChargebackStatus;
import uy.pensiones.enums.PaymentProvider;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "payment_chargebacks")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PaymentChargeback {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_id")
    private PaymentRecord payment;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private PaymentProvider provider;

    @Column(name = "provider_chargeback_id", nullable = false, length = 190)
    private String providerChargebackId;

    @Column(name = "provider_payment_id", length = 190)
    private String providerPaymentId;

    @Column(length = 3)
    private String currency;

    @Column(precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(length = 160)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private PaymentChargebackStatus status = PaymentChargebackStatus.OPEN;

    @Column(name = "coverage_eligible")
    private Boolean coverageEligible;

    @Column(name = "coverage_applied")
    private Boolean coverageApplied;

    @Column(name = "documentation_required")
    private Boolean documentationRequired;

    @Column(name = "documentation_status", length = 80)
    private String documentationStatus;

    @Column(name = "documentation_deadline")
    private OffsetDateTime documentationDeadline;

    @Enumerated(EnumType.STRING)
    @Column(name = "documentation_submission_state", length = 20)
    private ChargebackDocumentationSubmissionState documentationSubmissionState;

    @Column(name = "documentation_submission_started_at")
    private OffsetDateTime documentationSubmissionStartedAt;

    @Column(name = "documentation_submitted_at")
    private OffsetDateTime documentationSubmittedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "documentation_submitted_by_backoffice_user_id")
    private BackofficeUser documentationSubmittedByBackoffice;

    @Column(name = "documentation_submission_reason", length = 1500)
    private String documentationSubmissionReason;

    @Column(name = "documentation_file_count")
    private Integer documentationFileCount;

    @Column(name = "documentation_total_bytes")
    private Long documentationTotalBytes;

    @Column(name = "documentation_manifest_json", columnDefinition = "text")
    private String documentationManifestJson;

    @Column(name = "live_mode")
    private Boolean liveMode;

    @Column(name = "provider_created_at")
    private OffsetDateTime providerCreatedAt;

    @Column(name = "provider_updated_at")
    private OffsetDateTime providerUpdatedAt;

    @Column(name = "last_synced_at", nullable = false)
    private OffsetDateTime lastSyncedAt;

    @Column(name = "last_reconciliation_attempt_at")
    private OffsetDateTime lastReconciliationAttemptAt;

    @Column(name = "last_reconciliation_error", length = 600)
    private String lastReconciliationError;

    @Enumerated(EnumType.STRING)
    @Column(name = "benefit_decision", length = 30)
    private ChargebackBenefitDecision benefitDecision;

    @Column(name = "benefit_decided_at")
    private OffsetDateTime benefitDecidedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "benefit_decided_by_backoffice_user_id")
    private BackofficeUser benefitDecidedByBackoffice;

    @Column(name = "benefit_reason", length = 1500)
    private String benefitReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (lastSyncedAt == null) lastSyncedAt = now;
        if (status == null) status = PaymentChargebackStatus.OPEN;
    }

    @PreUpdate
    void preUpdate() { updatedAt = OffsetDateTime.now(); }
}
