// uy/pensiones/model/User.java
package uy.pensiones.model;

import jakarta.persistence.*;
import lombok.*;
import uy.pensiones.enums.UserRole;

import java.time.OffsetDateTime;

@Entity
@Table(name = "users")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class User {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, unique = true, length = 190)
  private String email;

  private String name;

  @Column(length = 30)
  private String phone;

  // ISO 3166-1 alpha-2, ej: UY, AR, BR, US
  @Column(length = 2)
  private String countryCode;

  @Column(nullable = false)
  private boolean emailVerified = false;

  @Column(length = 40)
  private String provider;   // GOOGLE, etc.

  private String providerId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private UserRole role = UserRole.SEEKER;

  @Column(nullable = false, updatable = false)
  private OffsetDateTime createdAt;

  @Column(name = "terms_accepted_version", length = 20)
  private String termsAcceptedVersion;

  @Column(name = "privacy_accepted_version", length = 20)
  private String privacyAcceptedVersion;

  @Column(name = "legal_accepted_at")
  private OffsetDateTime legalAcceptedAt;

  @Column(nullable = false, columnDefinition = "boolean default false")
  @Builder.Default
  private boolean suspended = false;

  @Column(name = "suspended_at")
  private OffsetDateTime suspendedAt;

  @Column(name = "suspension_reason", length = 500)
  private String suspensionReason;

  @Column(nullable = false)
  private OffsetDateTime updatedAt;

  @PrePersist
  void prePersist() {
    var now = OffsetDateTime.now();
    if (createdAt == null) createdAt = now;
    if (updatedAt == null) updatedAt = now;
  }

  @PreUpdate
  void preUpdate() {
    updatedAt = OffsetDateTime.now();
  }
}
