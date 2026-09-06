package uy.pensiones.model;

import jakarta.persistence.*;
import lombok.*;
import uy.pensiones.enums.LaunchCampaignEventType;

import java.time.OffsetDateTime;

@Entity
@Table(name = "launch_campaign_events")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class LaunchCampaignEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "campaign_id", nullable = false)
    private LaunchCampaign campaign;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "beneficiary_id")
    private LaunchCampaignBeneficiary beneficiary;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pension_id")
    private Pension pension;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "backoffice_user_id")
    private BackofficeUser backofficeUser;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 30)
    private LaunchCampaignEventType eventType;

    @Column(length = 1500)
    private String reason;

    @Column(name = "before_json", columnDefinition = "text")
    private String beforeJson;

    @Column(name = "after_json", columnDefinition = "text")
    private String afterJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
}
