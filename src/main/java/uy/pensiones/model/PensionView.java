package uy.pensiones.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "pension_views",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_pension_views_pension_visitor_day",
                columnNames = {"pension_id", "visitor_hash", "viewed_on"}
        ),
        indexes = {
                @Index(name = "idx_pension_views_pension_day", columnList = "pension_id,viewed_on"),
                @Index(name = "idx_pension_views_day", columnList = "viewed_on")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PensionView {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pension_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Pension pension;

    @Column(name = "visitor_hash", nullable = false, length = 64)
    private String visitorHash;

    @Column(name = "viewed_on", nullable = false)
    private LocalDate viewedOn;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
