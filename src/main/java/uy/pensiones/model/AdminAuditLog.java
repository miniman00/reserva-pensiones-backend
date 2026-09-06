package uy.pensiones.model;

import jakarta.persistence.*;
import lombok.*;
import uy.pensiones.enums.AdminAuditAction;
import uy.pensiones.enums.AdminAuditEntityType;

import java.time.OffsetDateTime;

@Entity
@Table(name = "admin_audit_log", indexes = {
        @Index(name = "idx_admin_audit_log_created_at", columnList = "created_at"),
        @Index(name = "idx_admin_audit_log_actor", columnList = "backoffice_user_id,created_at"),
        @Index(name = "idx_admin_audit_log_action", columnList = "action,created_at"),
        @Index(name = "idx_admin_audit_log_entity", columnList = "entity_type,entity_id,created_at")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AdminAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "backoffice_user_id", nullable = false)
    private BackofficeUser backofficeUser;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 80)
    private AdminAuditAction action;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false, length = 50)
    private AdminAuditEntityType entityType;

    @Column(name = "entity_id", nullable = false, length = 120)
    private String entityId;

    @Column(name = "before_json", columnDefinition = "text")
    private String beforeJson;

    @Column(name = "after_json", columnDefinition = "text")
    private String afterJson;

    @Column(length = 1500)
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
}
