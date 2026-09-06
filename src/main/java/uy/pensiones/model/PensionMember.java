package uy.pensiones.model;

import jakarta.persistence.*;
import lombok.Data;
import uy.pensiones.enums.PensionRole;

import java.time.OffsetDateTime;

@Entity
@Table(name = "pension_members",
        uniqueConstraints = @UniqueConstraint(columnNames = {"pension_id","user_id"}))
@Data
public class PensionMember {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    private Pension pension;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PensionRole role = PensionRole.AVAIL_ONLY;

    @Column(nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    // getters/setters
    // ...
}
