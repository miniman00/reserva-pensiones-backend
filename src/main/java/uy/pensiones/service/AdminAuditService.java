package uy.pensiones.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import uy.pensiones.enums.AdminAuditAction;
import uy.pensiones.enums.AdminAuditEntityType;
import uy.pensiones.model.AdminAuditLog;
import uy.pensiones.model.BackofficeUser;
import uy.pensiones.repo.AdminAuditLogRepository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Service
public class AdminAuditService {

    public static final int MAX_REASON_LENGTH = 1500;

    private final AdminAuditLogRepository logs;
    private final ObjectMapper objectMapper;

    public AdminAuditService(AdminAuditLogRepository logs, ObjectMapper objectMapper) {
        this.logs = logs;
        this.objectMapper = objectMapper;
    }

    /**
     * Registra la acción dentro de la transacción llamadora. Si la bitácora no se puede persistir,
     * la operación administrativa también debe fallar para evitar cambios críticos sin auditoría.
     */
    @Transactional
    public void record(BackofficeUser actor,
                       AdminAuditAction action,
                       AdminAuditEntityType entityType,
                       Object entityId,
                       Object before,
                       Object after,
                       String reason) {
        if (actor == null || actor.getId() == null) {
            throw new IllegalArgumentException("La acción administrativa requiere un usuario interno persistido");
        }
        if (action == null || entityType == null || entityId == null) {
            throw new IllegalArgumentException("La acción, entidad e identificador de auditoría son obligatorios");
        }

        AdminAuditLog log = AdminAuditLog.builder()
                .backofficeUser(actor)
                .action(action)
                .entityType(entityType)
                .entityId(String.valueOf(entityId))
                .beforeJson(toJson(before))
                .afterJson(toJson(after))
                .reason(optionalReason(reason))
                .build();
        logs.save(log);
    }

    @Transactional(readOnly = true)
    public Page<AdminAuditDTO> list(Long actorId,
                                    String actor,
                                    AdminAuditAction action,
                                    AdminAuditEntityType entityType,
                                    String entityId,
                                    LocalDate createdFrom,
                                    LocalDate createdTo,
                                    int page,
                                    int size) {
        if (createdFrom != null && createdTo != null && createdTo.isBefore(createdFrom)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La fecha final no puede ser anterior a la fecha inicial");
        }

        int safePage = Math.max(page, 0);
        int safeSize = Math.max(1, Math.min(size, 100));
        Pageable pageable = PageRequest.of(safePage, safeSize,
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id")));

        OffsetDateTime from = createdFrom == null ? null : createdFrom.atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime toExclusive = createdTo == null ? null : createdTo.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);
        String cleanEntityId = cleanEntityId(entityId);
        String cleanActor = cleanActor(actor);

        Specification<AdminAuditLog> spec = Specification.where(null);
        if (actorId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.<BackofficeUser>get("backofficeUser").<Long>get("id"), actorId));
        }
        if (cleanActor != null) {
            String like = "%" + cleanActor.toLowerCase(java.util.Locale.ROOT) + "%";
            spec = spec.and((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.<BackofficeUser>get("backofficeUser").<String>get("username")), like),
                    cb.like(cb.lower(root.<BackofficeUser>get("backofficeUser").<String>get("displayName")), like)
            ));
        }
        if (action != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.<AdminAuditAction>get("action"), action));
        }
        if (entityType != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.<AdminAuditEntityType>get("entityType"), entityType));
        }
        if (cleanEntityId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.<String>get("entityId"), cleanEntityId));
        }
        if (from != null) {
            spec = spec.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.<OffsetDateTime>get("createdAt"), from));
        }
        if (toExclusive != null) {
            spec = spec.and((root, query, cb) -> cb.lessThan(root.<OffsetDateTime>get("createdAt"), toExclusive));
        }

        return logs.findAll(spec, pageable).map(this::toDto);
    }

    public String requireReason(String value) {
        String reason = optionalReason(value);
        if (reason == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Debes indicar el motivo de la acción administrativa");
        }
        return reason;
    }

    private String optionalReason(String value) {
        if (value == null || value.isBlank()) return null;
        String reason = value.trim();
        if (reason.length() > MAX_REASON_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El motivo no puede superar " + MAX_REASON_LENGTH + " caracteres");
        }
        return reason;
    }

    private String cleanActor(String value) {
        if (value == null || value.isBlank()) return null;
        String result = value.trim();
        if (result.length() > 160) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El filtro de usuario interno no puede superar 160 caracteres");
        }
        return result;
    }

    private String cleanEntityId(String value) {
        if (value == null || value.isBlank()) return null;
        String result = value.trim();
        if (result.length() > 120) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El identificador de entidad no puede superar 120 caracteres");
        }
        return result;
    }

    private String toJson(Object value) {
        if (value == null) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("No se pudo serializar la instantánea de auditoría", ex);
        }
    }

    private AdminAuditDTO toDto(AdminAuditLog log) {
        BackofficeUser actor = log.getBackofficeUser();
        return new AdminAuditDTO(
                log.getId(), actor.getId(), actor.getUsername(), actor.getDisplayName(),
                log.getAction(), log.getEntityType(), log.getEntityId(), log.getBeforeJson(),
                log.getAfterJson(), log.getReason(), log.getCreatedAt()
        );
    }

    public record AdminAuditDTO(
            Long id,
            Long backofficeUserId,
            String username,
            String displayName,
            AdminAuditAction action,
            AdminAuditEntityType entityType,
            String entityId,
            String beforeJson,
            String afterJson,
            String reason,
            OffsetDateTime createdAt
    ) {}
}
