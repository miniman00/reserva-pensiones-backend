package uy.pensiones.model;

import jakarta.persistence.*;
import lombok.*;
import uy.pensiones.enums.PaymentProvider;
import uy.pensiones.enums.PaymentProviderCertificationStatus;
import uy.pensiones.enums.PaymentProviderMode;

import java.time.OffsetDateTime;

@Entity
@Table(name = "payment_provider_certification_runs")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PaymentProviderCertificationRun {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private PaymentProvider provider;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentProviderCertificationStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "started_mode", nullable = false, length = 20)
    private PaymentProviderMode startedMode;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "started_by_backoffice_user_id", nullable = false)
    private BackofficeUser startedByBackoffice;

    @Column(name = "started_at", nullable = false, updatable = false)
    private OffsetDateTime startedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pre_live_validated_by_backoffice_user_id")
    private BackofficeUser preLiveValidatedByBackoffice;

    @Column(name = "pre_live_validated_at")
    private OffsetDateTime preLiveValidatedAt;

    @Column(name = "pre_live_snapshot_json", columnDefinition = "text")
    private String preLiveSnapshotJson;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "aborted_at")
    private OffsetDateTime abortedAt;

    @Column(name = "completion_snapshot_json", columnDefinition = "text")
    private String completionSnapshotJson;

    @Column(name = "abort_reason", length = 1500)
    private String abortReason;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (status == null) status = PaymentProviderCertificationStatus.ACTIVE;
        if (startedAt == null) startedAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void preUpdate() { updatedAt = OffsetDateTime.now(); }
}
