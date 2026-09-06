package uy.pensiones.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.OffsetDateTime;

@Entity
@Table(name = "pension_favorites",
        uniqueConstraints = @UniqueConstraint(name = "uk_pension_favorites_user_pension", columnNames = {"user_id", "pension_id"}),
        indexes = {
                @Index(name = "idx_pension_favorites_user_created", columnList = "user_id,created_at"),
                @Index(name = "idx_pension_favorites_pension", columnList = "pension_id")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PensionFavorite {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pension_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Pension pension;

    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
}
