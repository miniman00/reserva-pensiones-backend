package uy.pensiones.model;

import jakarta.persistence.*;
import lombok.*;
import uy.pensiones.enums.PaymentEventSource;
import uy.pensiones.enums.PaymentStatus;

import java.time.OffsetDateTime;

@Entity
@Table(name = "payment_status_history")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PaymentStatusHistory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_id", nullable = false)
    private PaymentRecord payment;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 30)
    private PaymentStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 30)
    private PaymentStatus toStatus;

    @Column(name = "provider_status", length = 80)
    private String providerStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_source", nullable = false, length = 30)
    private PaymentEventSource eventSource;

    @Column(length = 500)
    private String note;

    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
}
