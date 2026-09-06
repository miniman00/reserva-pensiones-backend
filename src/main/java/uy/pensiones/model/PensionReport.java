package uy.pensiones.model;

import jakarta.persistence.*;
import lombok.*;
import uy.pensiones.enums.PensionReportReason;
import uy.pensiones.enums.PensionReportResolution;
import uy.pensiones.enums.PensionReportStatus;

import java.time.OffsetDateTime;

@Entity
@Table(name = "pension_reports", indexes = {
        @Index(name = "idx_pension_reports_status_created", columnList = "status,created_at"),
        @Index(name = "idx_pension_reports_pension", columnList = "pension_id")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PensionReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pension_id", nullable = false)
    private Pension pension;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reporter_user_id")
    private User reporter;

    @Column(name = "reporter_key_hash", length = 64)
    private String reporterKeyHash;

    @Column(name = "reporter_email", length = 190)
    private String reporterEmail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private PensionReportReason reason;

    @Column(length = 1200)
    private String details;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private PensionReportStatus status = PensionReportStatus.NEW;

    @Enumerated(EnumType.STRING)
    @Column(length = 40)
    private PensionReportResolution resolution;

    @Column(name = "admin_notes", length = 1500)
    private String adminNotes;

    /** Revisor legado de cuando el Backoffice reutilizaba users/OAuth. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by_user_id")
    private User reviewedBy;

    /** Usuario interno que revisó la denuncia en el Backoffice actual. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by_backoffice_user_id")
    private BackofficeUser reviewedByBackoffice;

    @Column(name = "reviewed_at")
    private OffsetDateTime reviewedAt;

    @Column(name = "terms_accepted_version", length = 20)
    private String termsAcceptedVersion;

    @Column(name = "privacy_accepted_version", length = 20)
    private String privacyAcceptedVersion;

    @Column(name = "consent_accepted_at")
    private OffsetDateTime consentAcceptedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (status == null) status = PensionReportStatus.NEW;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
