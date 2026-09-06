package uy.pensiones.model;

import jakarta.persistence.*;
import lombok.*;
import uy.pensiones.enums.PaymentProvider;

import java.time.OffsetDateTime;

@Entity
@Table(name = "payment_provider_events")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PaymentProviderEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private PaymentProvider provider;

    @Column(name = "provider_event_id", nullable = false, length = 190)
    private String providerEventId;

    @Column(name = "payload_hash", length = 64)
    private String payloadHash;

    @Column(name = "event_type", length = 80)
    private String eventType;

    @Column(name = "event_action", length = 120)
    private String eventAction;

    @Column(name = "resource_id", length = 190)
    private String resourceId;

    @Column(name = "request_id", length = 190)
    private String requestId;

    @Column(name = "live_mode")
    private Boolean liveMode;

    @Column(name = "processed_at")
    private OffsetDateTime processedAt;

    @Column(name = "processing_error", length = 500)
    private String processingError;

    @Column(name = "manual_replay_in_progress", nullable = false)
    private boolean manualReplayInProgress;

    @Column(name = "manual_replay_started_at")
    private OffsetDateTime manualReplayStartedAt;

    @Column(name = "last_manual_replay_at")
    private OffsetDateTime lastManualReplayAt;

    @Column(name = "manual_replay_count", nullable = false)
    private int manualReplayCount;

    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
}
