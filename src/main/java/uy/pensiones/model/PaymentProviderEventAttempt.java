package uy.pensiones.model;

import jakarta.persistence.*;
import lombok.*;
import uy.pensiones.enums.PaymentProviderEventAttemptSource;
import uy.pensiones.enums.PaymentProviderEventAttemptStatus;

import java.time.OffsetDateTime;

@Entity
@Table(name = "payment_provider_event_attempts")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PaymentProviderEventAttempt {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private PaymentProviderEvent event;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PaymentProviderEventAttemptSource source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PaymentProviderEventAttemptStatus status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "backoffice_user_id")
    private BackofficeUser backofficeUser;

    @Column(length = 1500)
    private String reason;

    @Column(name = "result_message", length = 500)
    private String resultMessage;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(name = "started_at", nullable = false, updatable = false)
    private OffsetDateTime startedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @PrePersist
    void prePersist() {
        if (startedAt == null) startedAt = OffsetDateTime.now();
        if (status == null) status = PaymentProviderEventAttemptStatus.IN_PROGRESS;
    }
}
