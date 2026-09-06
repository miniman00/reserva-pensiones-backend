package uy.pensiones.model;

import jakarta.persistence.*;
import lombok.Data;
import uy.pensiones.enums.InviteStatus;
import uy.pensiones.enums.PensionRole;

import java.time.OffsetDateTime;

@Entity
@Table(name = "pension_invites", indexes = {
        @Index(columnList = "token", unique = true),
        @Index(columnList = "email")
})
@Data
public class PensionInvite {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    private Pension pension;

    @Column(nullable = false, length = 320)
    private String email; // guardá en lower-case

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PensionRole role = PensionRole.AVAIL_ONLY;

    @Column(nullable = false, unique = true, length = 64)
    private String token;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InviteStatus status = InviteStatus.PENDING;

    @Column(nullable = false)
    private OffsetDateTime expiresAt;

    @Column(nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    private Long createdByUserId;
    private Long acceptedByUserId;

    // getters/setters
    // ...
}
