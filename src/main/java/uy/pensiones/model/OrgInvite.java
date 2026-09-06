package uy.pensiones.model;

import jakarta.persistence.*;
import lombok.*;
import uy.pensiones.enums.InviteStatus;

import java.time.OffsetDateTime;

@Entity
@Table(name = "org_invites")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class OrgInvite {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Organization org;

    @Column(nullable = false, length = 190)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Membership.Role role;

    @Column(nullable = false, length = 200)
    private String tokenHash;

    @Column(nullable = false)
    private OffsetDateTime expiresAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InviteStatus status;

    private OffsetDateTime acceptedAt;

    @Column(nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    public void prePersist() {
        this.createdAt = OffsetDateTime.now();
        if (this.status == null) this.status = InviteStatus.PENDING;
        if (this.expiresAt == null) this.expiresAt = OffsetDateTime.now().plusDays(2);
    }


}
