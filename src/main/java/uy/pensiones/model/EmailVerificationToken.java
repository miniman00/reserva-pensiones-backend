package uy.pensiones.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "email_verification_tokens")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class EmailVerificationToken {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private User user;

    @Column(nullable = false, length = 200)
    private String tokenHash;

    @Column(nullable = false)
    private OffsetDateTime expiresAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(nullable = false)
    private OffsetDateTime createdAt;

    private OffsetDateTime acceptedAt;

    @PrePersist
    public void prePersist() {
        this.createdAt = OffsetDateTime.now();
        if (this.status == null) this.status = Status.PENDING;
        if (this.expiresAt == null) this.expiresAt = OffsetDateTime.now().plusDays(2);
    }

    public enum Status { PENDING, ACCEPTED, EXPIRED, REVOKED }
}
