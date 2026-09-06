package uy.pensiones.service;

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
import uy.pensiones.enums.PensionStatus;
import uy.pensiones.enums.UserRole;
import uy.pensiones.model.BackofficeUser;
import uy.pensiones.model.User;
import uy.pensiones.repo.AdminUserQueryRepository;
import uy.pensiones.repo.UserRepository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class AdminUserService {

    private final UserRepository users;
    private final AdminUserQueryRepository adminUsers;
    private final AdminAuditService audit;

    public AdminUserService(UserRepository users, AdminUserQueryRepository adminUsers, AdminAuditService audit) {
        this.users = users;
        this.adminUsers = adminUsers;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public Page<AdminUserDTO> list(String q, UserRole role, Boolean suspended, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.max(1, Math.min(size, 100));
        Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt"));

        Specification<User> spec = Specification.where(
                (root, query, cb) -> root.get("role").in(UserRole.SEEKER, UserRole.OWNER)
        );
        String term = clean(q);
        if (term != null) {
            String like = "%" + term.toLowerCase(Locale.ROOT) + "%";
            spec = spec.and((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.<String>get("email")), like),
                    cb.like(cb.lower(root.<String>get("name")), like)
            ));
        }
        if (role != null) {
            if (role == UserRole.SEEKER || role == UserRole.OWNER) {
                spec = spec.and((root, query, cb) -> cb.equal(root.get("role"), role));
            } else {
                // ADMIN es un valor legado de users/OAuth y nunca forma parte de la gestión del marketplace.
                spec = spec.and((root, query, cb) -> cb.disjunction());
            }
        }
        if (suspended != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("suspended"), suspended));
        }

        return users.findAll(spec, pageable).map(this::toDto);
    }

    @Transactional(readOnly = true)
    public AdminUserSummaryDTO summary() {
        AdminUserQueryRepository.AdminUserSummaryRow row = adminUsers.findSummary();
        return new AdminUserSummaryDTO(
                safe(row == null ? null : row.getTotal()),
                safe(row == null ? null : row.getOwners()),
                safe(row == null ? null : row.getSuspended()),
                safe(row == null ? null : row.getVerified())
        );
    }

    @Transactional(readOnly = true)
    public AdminUserDetailDTO detail(Long userId) {
        User target = findUser(userId);
        AdminUserQueryRepository.AdminUserActivityRow activity = adminUsers.findActivity(target.getId());
        return new AdminUserDetailDTO(
                toDto(target),
                new AdminUserLegalDTO(
                        target.getTermsAcceptedVersion(), target.getPrivacyAcceptedVersion(), target.getLegalAcceptedAt()
                ),
                activityDto(activity),
                adminUsers.findRecentResponsiblePensions(target.getId()).stream().map(this::pensionDto).toList(),
                adminUsers.findRecentCollaboratingPensions(target.getId()).stream().map(this::pensionDto).toList()
        );
    }

    @Transactional
    public AdminUserDTO changeRole(Long userId, UserRole role, String rawReason, BackofficeUser actor) {
        if (role == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Debes indicar el rol");
        }
        if (role == UserRole.ADMIN) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Los administradores del Backoffice se gestionan como usuarios internos, no como usuarios de Pensiones");
        }
        User target = findUser(userId);
        if (target.getRole() == role) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "El usuario ya tiene ese rol");
        }
        String reason = audit.requireReason(rawReason);
        Map<String, Object> before = snapshot(target);
        target.setRole(role);
        User saved = users.save(target);
        audit.record(actor, AdminAuditAction.ADMIN_CHANGE_USER_ROLE, AdminAuditEntityType.MARKETPLACE_USER,
                saved.getId(), before, snapshot(saved), reason);
        return toDto(saved);
    }

    @Transactional
    public AdminUserDTO suspend(Long userId, String rawReason, BackofficeUser actor) {
        User target = findUser(userId);
        if (target.isSuspended()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "La cuenta ya está suspendida");
        }
        String reason = requireSuspensionReason(rawReason);
        Map<String, Object> before = snapshot(target);

        target.setSuspended(true);
        target.setSuspendedAt(OffsetDateTime.now(ZoneOffset.UTC));
        target.setSuspensionReason(reason);
        User saved = users.save(target);
        audit.record(actor, AdminAuditAction.ADMIN_SUSPEND_USER, AdminAuditEntityType.MARKETPLACE_USER,
                saved.getId(), before, snapshot(saved), reason);
        return toDto(saved);
    }

    @Transactional
    public AdminUserDTO reactivate(Long userId, String rawReason, BackofficeUser actor) {
        User target = findUser(userId);
        if (!target.isSuspended()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "La cuenta no está suspendida");
        }
        String reason = audit.requireReason(rawReason);
        Map<String, Object> before = snapshot(target);
        target.setSuspended(false);
        target.setSuspendedAt(null);
        target.setSuspensionReason(null);
        User saved = users.save(target);
        audit.record(actor, AdminAuditAction.ADMIN_REACTIVATE_USER, AdminAuditEntityType.MARKETPLACE_USER,
                saved.getId(), before, snapshot(saved), reason);
        return toDto(saved);
    }

    private User findUser(Long userId) {
        User user = users.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuario de marketplace no encontrado"));
        if (user.getRole() == UserRole.ADMIN) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuario de marketplace no encontrado");
        }
        return user;
    }

    private String requireSuspensionReason(String value) {
        String reason = audit.requireReason(value);
        if (reason.length() > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El motivo de suspensión no puede superar 500 caracteres");
        }
        return reason;
    }

    private Map<String, Object> snapshot(User user) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", user.getId());
        value.put("email", user.getEmail());
        value.put("name", user.getName());
        value.put("role", user.getRole());
        value.put("suspended", user.isSuspended());
        value.put("suspendedAt", user.getSuspendedAt());
        value.put("suspensionReason", user.getSuspensionReason());
        return value;
    }

    private AdminUserDTO toDto(User user) {
        return new AdminUserDTO(
                user.getId(), user.getName(), user.getEmail(), user.getRole(), user.isEmailVerified(),
                user.getProvider(), user.getPhone(), user.getCountryCode(), user.isSuspended(),
                user.getSuspendedAt(), user.getSuspensionReason(), user.getCreatedAt(), user.getUpdatedAt()
        );
    }

    private AdminUserActivityDTO activityDto(AdminUserQueryRepository.AdminUserActivityRow row) {
        return new AdminUserActivityDTO(
                safe(row == null ? null : row.getOrganizationCount()),
                safe(row == null ? null : row.getActiveMembershipCount()),
                safe(row == null ? null : row.getResponsiblePensionCount()),
                safe(row == null ? null : row.getPublishedPensionCount()),
                safe(row == null ? null : row.getPausedPensionCount()),
                safe(row == null ? null : row.getDraftPensionCount()),
                safe(row == null ? null : row.getBlockedPensionCount()),
                safe(row == null ? null : row.getPublicVisibilityPensionCount()),
                safe(row == null ? null : row.getCollaborationCount()),
                safe(row == null ? null : row.getSentInquiryCount()),
                safe(row == null ? null : row.getFavoriteCount()),
                safe(row == null ? null : row.getSubmittedReportCount()),
                safe(row == null ? null : row.getReceivedReportCount()),
                safe(row == null ? null : row.getOpenReceivedReportCount()),
                safe(row == null ? null : row.getReceivedInquiryCount()),
                safe(row == null ? null : row.getReceivedViewCount()),
                safe(row == null ? null : row.getReceivedFavoriteCount())
        );
    }

    private AdminUserPensionDTO pensionDto(AdminUserQueryRepository.AdminUserPensionRow row) {
        return new AdminUserPensionDTO(
                row.getId(), row.getName(), row.getOrganizationName(), row.getCity(), row.getState(),
                row.getStatus() == null ? null : PensionStatus.valueOf(row.getStatus()),
                Boolean.TRUE.equals(row.getModerationBlocked()), Boolean.TRUE.equals(row.getFeatured()),
                row.getRelationshipRole(), safe(row.getReportCount()), safe(row.getOpenReportCount()), row.getUpdatedAt()
        );
    }

    private long safe(Long value) {
        return value == null ? 0L : Math.max(value, 0L);
    }

    private String clean(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }

    public record AdminUserDTO(
            Long id,
            String name,
            String email,
            UserRole role,
            boolean emailVerified,
            String provider,
            String phone,
            String countryCode,
            boolean suspended,
            OffsetDateTime suspendedAt,
            String suspensionReason,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {}

    public record AdminUserSummaryDTO(long total, long owners, long suspended, long verified) {}

    public record AdminUserLegalDTO(
            String termsAcceptedVersion,
            String privacyAcceptedVersion,
            OffsetDateTime legalAcceptedAt
    ) {}

    public record AdminUserActivityDTO(
            long organizationCount,
            long activeMembershipCount,
            long responsiblePensionCount,
            long publishedPensionCount,
            long pausedPensionCount,
            long draftPensionCount,
            long blockedPensionCount,
            long publicVisibilityPensionCount,
            long collaborationCount,
            long sentInquiryCount,
            long favoriteCount,
            long submittedReportCount,
            long receivedReportCount,
            long openReceivedReportCount,
            long receivedInquiryCount,
            long receivedViewCount,
            long receivedFavoriteCount
    ) {}

    public record AdminUserPensionDTO(
            Long id,
            String name,
            String organizationName,
            String city,
            String state,
            PensionStatus status,
            boolean moderationBlocked,
            boolean featured,
            String relationshipRole,
            long reportCount,
            long openReportCount,
            OffsetDateTime updatedAt
    ) {}

    public record AdminUserDetailDTO(
            AdminUserDTO user,
            AdminUserLegalDTO legal,
            AdminUserActivityDTO activity,
            List<AdminUserPensionDTO> responsiblePensions,
            List<AdminUserPensionDTO> collaboratingPensions
    ) {}
}
