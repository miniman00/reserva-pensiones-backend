package uy.pensiones.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import uy.pensiones.enums.InquiryMessageSenderRole;

import java.time.OffsetDateTime;

@Entity
@Table(name = "pension_inquiry_messages", indexes = {
        @Index(name = "idx_pension_inquiry_messages_inquiry_created", columnList = "inquiry_id,created_at,id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PensionInquiryMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "inquiry_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private PensionInquiry inquiry;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sender_user_id")
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private User sender;

    @Enumerated(EnumType.STRING)
    @Column(name = "sender_role", nullable = false, length = 20)
    private InquiryMessageSenderRole senderRole;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recipient_user_id")
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private User recipient;

    @Column(name = "read_at")
    private OffsetDateTime readAt;

    @Column(nullable = false, length = 2000)
    private String body;

    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
}
