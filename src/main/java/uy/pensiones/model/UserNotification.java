package uy.pensiones.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import uy.pensiones.enums.NotificationType;

import java.time.OffsetDateTime;

@Entity
@Table(name = "user_notifications", indexes = {
        @Index(name = "idx_user_notifications_user_created", columnList = "user_id,created_at"),
        @Index(name = "idx_user_notifications_user_read", columnList = "user_id,read_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserNotification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private NotificationType type;

    @Column(nullable = false, length = 180)
    private String title;

    @Column(length = 700)
    private String message;

    @Column(length = 500)
    private String link;

    private OffsetDateTime readAt;

    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
}
