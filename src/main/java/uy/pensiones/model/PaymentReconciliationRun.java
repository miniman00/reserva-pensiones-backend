package uy.pensiones.model;

import jakarta.persistence.*;
import lombok.*;
import uy.pensiones.enums.PaymentReconciliationRunStatus;
import uy.pensiones.enums.PaymentReconciliationTrigger;

import java.time.OffsetDateTime;

@Entity
@Table(name = "payment_reconciliation_runs")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PaymentReconciliationRun {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 20)
    private PaymentReconciliationTrigger triggerType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentReconciliationRunStatus status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "started_by_backoffice_user_id")
    private BackofficeUser startedByBackoffice;

    @Column(name = "lease_owner", nullable = false, length = 120)
    private String leaseOwner;

    @Column(name = "started_at", nullable = false, updatable = false)
    private OffsetDateTime startedAt;
    @Column(name = "completed_at") private OffsetDateTime completedAt;

    @Column(name = "payments_checked", nullable = false) private int paymentsChecked;
    @Column(name = "payments_changed", nullable = false) private int paymentsChanged;
    @Column(name = "refunds_checked", nullable = false) private int refundsChecked;
    @Column(name = "refunds_changed", nullable = false) private int refundsChanged;
    @Column(name = "chargebacks_checked", nullable = false) private int chargebacksChecked;
    @Column(name = "chargebacks_changed", nullable = false) private int chargebacksChanged;
    @Column(name = "errors_count", nullable = false) private int errorsCount;
    @Column(name = "summary_message", length = 1000) private String summaryMessage;

    @PrePersist void prePersist() {
        if (startedAt == null) startedAt = OffsetDateTime.now();
        if (status == null) status = PaymentReconciliationRunStatus.RUNNING;
    }
}
