package uy.pensiones.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import uy.pensiones.enums.InquiryClosureReason;
import uy.pensiones.enums.InquiryRoomType;
import uy.pensiones.enums.InquiryStatus;

import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "pension_inquiries", indexes = {
        @Index(name = "idx_pension_inquiries_pension_created", columnList = "pension_id,created_at"),
        @Index(name = "idx_pension_inquiries_status", columnList = "status"),
        @Index(name = "idx_pension_inquiries_requester_created", columnList = "requester_id,created_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PensionInquiry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pension_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Pension pension;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requester_id")
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private User requester;

    @Column(nullable = false, length = 120)
    private String contactName;

    @Column(length = 190)
    private String contactEmail;

    @Column(length = 40)
    private String contactPhone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InquiryRoomType roomType;

    private LocalDate moveInDate;

    @Column(length = 2000)
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InquiryStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "closure_reason", length = 30)
    private InquiryClosureReason closureReason;

    @Column(name = "closed_at")
    private OffsetDateTime closedAt;

    @Column(name = "converted_at")
    private OffsetDateTime convertedAt;

    /** Promoción pública desde la que el usuario llegó a la ficha, si pudo validarse al enviar la consulta. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "attributed_promotion_id")
    private PensionPromotion attributedPromotion;

    /** Snapshot mínimo para conservar contexto comercial aunque el catálogo cambie después. */
    @Column(name = "attributed_promotion_product_code", length = 80)
    private String attributedPromotionProductCode;

    @Column(name = "attributed_promotion_product_name", length = 160)
    private String attributedPromotionProductName;

    @Column(name = "attributed_promotion_target_type", length = 30)
    private String attributedPromotionTargetType;

    @Column(name = "terms_accepted_version", length = 20)
    private String termsAcceptedVersion;

    @Column(name = "privacy_accepted_version", length = 20)
    private String privacyAcceptedVersion;

    @Column(name = "consent_accepted_at")
    private OffsetDateTime consentAcceptedAt;

    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (roomType == null) roomType = InquiryRoomType.ANY;
        if (status == null) status = InquiryStatus.NEW;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
