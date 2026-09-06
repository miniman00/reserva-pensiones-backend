package uy.pensiones.service;

import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import uy.pensiones.enums.*;
import uy.pensiones.model.*;
import uy.pensiones.repo.OwnerSubscriptionRepository;
import uy.pensiones.repo.PlanRepository;
import uy.pensiones.repo.PlanVersionRepository;
import uy.pensiones.repo.AdminSubscriptionUserRepository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class AdminSubscriptionService {

    private static final int MAX_GRANT_DAYS = 3_650;

    private final OwnerSubscriptionRepository subscriptions;
    private final AdminSubscriptionUserRepository users;
    private final PlanRepository plans;
    private final PlanVersionRepository versions;
    private final AdminAuditService audit;

    public AdminSubscriptionService(OwnerSubscriptionRepository subscriptions,
                                    AdminSubscriptionUserRepository users,
                                    PlanRepository plans,
                                    PlanVersionRepository versions,
                                    AdminAuditService audit) {
        this.subscriptions = subscriptions;
        this.users = users;
        this.plans = plans;
        this.versions = versions;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public Page<SubscriptionDTO> list(String q,
                                      String planCode,
                                      SubscriptionSource source,
                                      SubscriptionStatus status,
                                      int page,
                                      int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.max(1, Math.min(size, 100));
        Pageable pageable = PageRequest.of(safePage, safeSize,
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id")));
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        Specification<OwnerSubscription> spec = Specification.where(
                (root, query, cb) -> {
                    Join<OwnerSubscription, User> user = root.join("user", JoinType.INNER);
                    return user.<UserRole>get("role").in(UserRole.SEEKER, UserRole.OWNER);
                }
        );

        String term = clean(q, 190, "El filtro de propietario");
        if (term != null) {
            String like = "%" + term.toLowerCase(Locale.ROOT) + "%";
            spec = spec.and((root, query, cb) -> {
                Join<OwnerSubscription, User> user = root.join("user", JoinType.INNER);
                return cb.or(
                        cb.like(cb.lower(user.<String>get("email")), like),
                        cb.like(cb.lower(user.<String>get("name")), like)
                );
            });
        }

        String cleanPlan = clean(planCode, 30, "El filtro de plan");
        if (cleanPlan != null) {
            String normalized = cleanPlan.toUpperCase(Locale.ROOT);
            spec = spec.and((root, query, cb) -> {
                var plan = root.join("planVersion", JoinType.INNER).join("plan", JoinType.INNER);
                return cb.equal(cb.upper(plan.<String>get("code")), normalized);
            });
        }

        if (source != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("source"), source));
        }
        if (status != null) {
            spec = spec.and(effectiveStatusSpec(status, now));
        }

        return subscriptions.findAll(spec, pageable).map(item -> dto(item, now));
    }

    @Transactional(readOnly = true)
    public SubscriptionSummaryDTO summary() {
        OwnerSubscriptionRepository.SubscriptionSummaryRow row =
                subscriptions.summary(OffsetDateTime.now(ZoneOffset.UTC));
        return new SubscriptionSummaryDTO(
                safe(row == null ? null : row.getTotal()),
                safe(row == null ? null : row.getActive()),
                safe(row == null ? null : row.getExpired()),
                safe(row == null ? null : row.getCancelled()),
                safe(row == null ? null : row.getAdminGrants())
        );
    }

    @Transactional(readOnly = true)
    public SubscriptionDTO detail(Long subscriptionId) {
        OwnerSubscription subscription = subscriptions.findDetailedById(requireId(subscriptionId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Suscripción no encontrada"));
        requireMarketplaceUser(subscription.getUser());
        return dto(subscription, OffsetDateTime.now(ZoneOffset.UTC));
    }

    @Transactional
    public SubscriptionDTO grant(Long userId,
                                 Long planVersionId,
                                 int durationDays,
                                 String rawReason,
                                 BackofficeUser actor) {
        validateDuration(durationDays);
        String reason = audit.requireReason(rawReason);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        User target = users.findByIdForUpdate(requireId(userId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuario no encontrado"));
        requireMarketplaceUser(target);
        if (target.isSuspended()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "No se puede otorgar un plan mientras la cuenta esté suspendida");
        }

        expireDueForUser(target.getId(), now);
        if (hasEffectiveSubscription(target.getId(), now)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "La cuenta ya tiene una suscripción vigente. Extiéndela o cancélala antes de otorgar otra");
        }

        Long versionPlanId = versions.findPlanIdByVersionId(requireId(planVersionId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Versión de plan no encontrada"));
        Plan plan = plans.findByIdForUpdate(versionPlanId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Plan no encontrado"));
        // Se relee la versión después de obtener el lock del plan. Publicar/retirar versiones
        // usa el mismo lock, evitando otorgar una versión que haya cambiado concurrentemente.
        PlanVersion version = versions.findById(planVersionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Versión de plan no encontrada"));
        validateGrantableVersion(plan, version, now);

        OwnerSubscription subscription = subscriptions.save(OwnerSubscription.builder()
                .user(target)
                .planVersion(version)
                .status(SubscriptionStatus.ACTIVE)
                .startedAt(now)
                .expiresAt(now.plusDays(durationDays))
                .source(SubscriptionSource.ADMIN_GRANT)
                .build());

        audit.record(actor, AdminAuditAction.ADMIN_GRANT_SUBSCRIPTION, AdminAuditEntityType.SUBSCRIPTION,
                subscription.getId(), null, snapshot(subscription, now), reason);
        return dto(subscription, now);
    }

    @Transactional
    public SubscriptionDTO extend(Long subscriptionId,
                                  int additionalDays,
                                  String rawReason,
                                  BackofficeUser actor) {
        validateDuration(additionalDays);
        String reason = audit.requireReason(rawReason);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Long userId = subscriptions.findUserIdById(requireId(subscriptionId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Suscripción no encontrada"));

        users.findByIdForUpdate(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuario no encontrado"));
        expireDueForUser(userId, now);
        OwnerSubscription subscription = subscriptions.findByIdForUpdate(subscriptionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Suscripción no encontrada"));
        requireMarketplaceUser(subscription.getUser());
        requireManualMutation(subscription, now, "extender");

        Map<String, Object> before = snapshot(subscription, now);
        subscription.setExpiresAt(subscription.getExpiresAt().plusDays(additionalDays));
        subscription = subscriptions.save(subscription);
        audit.record(actor, AdminAuditAction.ADMIN_EXTEND_SUBSCRIPTION, AdminAuditEntityType.SUBSCRIPTION,
                subscription.getId(), before, snapshot(subscription, now), reason);
        return dto(subscription, now);
    }

    @Transactional
    public SubscriptionDTO cancel(Long subscriptionId,
                                  String rawReason,
                                  BackofficeUser actor) {
        String reason = audit.requireReason(rawReason);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Long userId = subscriptions.findUserIdById(requireId(subscriptionId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Suscripción no encontrada"));

        users.findByIdForUpdate(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuario no encontrado"));
        expireDueForUser(userId, now);
        OwnerSubscription subscription = subscriptions.findByIdForUpdate(subscriptionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Suscripción no encontrada"));
        requireMarketplaceUser(subscription.getUser());
        requireManualMutation(subscription, now, "cancelar");

        Map<String, Object> before = snapshot(subscription, now);
        subscription.setStatus(SubscriptionStatus.CANCELLED);
        subscription.setCancelledAt(now);
        subscription.setCancellationReason(shortReason(reason));
        subscription = subscriptions.save(subscription);
        audit.record(actor, AdminAuditAction.ADMIN_CANCEL_SUBSCRIPTION, AdminAuditEntityType.SUBSCRIPTION,
                subscription.getId(), before, snapshot(subscription, now), reason);
        return dto(subscription, now);
    }

    private Specification<OwnerSubscription> effectiveStatusSpec(SubscriptionStatus status, OffsetDateTime now) {
        return (root, query, cb) -> switch (status) {
            case ACTIVE -> cb.and(
                    cb.equal(root.<SubscriptionStatus>get("status"), SubscriptionStatus.ACTIVE),
                    cb.lessThanOrEqualTo(root.<OffsetDateTime>get("startedAt"), now),
                    cb.greaterThan(root.<OffsetDateTime>get("expiresAt"), now)
            );
            case EXPIRED -> cb.or(
                    cb.equal(root.<SubscriptionStatus>get("status"), SubscriptionStatus.EXPIRED),
                    cb.and(
                            cb.equal(root.<SubscriptionStatus>get("status"), SubscriptionStatus.ACTIVE),
                            cb.lessThanOrEqualTo(root.<OffsetDateTime>get("expiresAt"), now)
                    )
            );
            case CANCELLED -> cb.equal(root.<SubscriptionStatus>get("status"), SubscriptionStatus.CANCELLED);
        };
    }

    private void validateGrantableVersion(Plan plan, PlanVersion version, OffsetDateTime now) {
        if (!plan.isActive()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "El plan está inactivo y no admite nuevas suscripciones");
        }
        if (version.getStatus() != PlanVersionStatus.PUBLISHED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Solo se puede otorgar una versión de plan publicada");
        }
        if (version.getEffectiveFrom() == null || now.isBefore(version.getEffectiveFrom())
                || (version.getEffectiveUntil() != null && !now.isBefore(version.getEffectiveUntil()))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "La versión seleccionada no está vigente en este momento");
        }
    }

    private void requireManualMutation(OwnerSubscription subscription, OffsetDateTime now, String action) {
        if (subscription.getSource() == SubscriptionSource.PAYMENT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Una suscripción de pago deberá " + action + "se mediante PaymentGateway desde el módulo Pagos y conciliación");
        }
        if (effectiveStatus(subscription, now) != SubscriptionStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Solo una suscripción vigente puede " + action + "se");
        }
    }

    private void expireDueForUser(Long userId, OffsetDateTime now) {
        List<OwnerSubscription> active = subscriptions.findByUserIdAndStatusOrderByExpiresAtDesc(userId, SubscriptionStatus.ACTIVE);
        for (OwnerSubscription subscription : active) {
            if (!subscription.getExpiresAt().isAfter(now)) {
                subscription.setStatus(SubscriptionStatus.EXPIRED);
            }
        }
    }

    private boolean hasEffectiveSubscription(Long userId, OffsetDateTime now) {
        return subscriptions.findByUserIdAndStatusOrderByExpiresAtDesc(userId, SubscriptionStatus.ACTIVE).stream()
                .anyMatch(item -> !now.isBefore(item.getStartedAt()) && now.isBefore(item.getExpiresAt()));
    }

    private SubscriptionStatus effectiveStatus(OwnerSubscription subscription, OffsetDateTime now) {
        if (subscription.getStatus() == SubscriptionStatus.CANCELLED) return SubscriptionStatus.CANCELLED;
        if (subscription.getStatus() == SubscriptionStatus.EXPIRED || !subscription.getExpiresAt().isAfter(now)) {
            return SubscriptionStatus.EXPIRED;
        }
        return SubscriptionStatus.ACTIVE;
    }

    private SubscriptionDTO dto(OwnerSubscription subscription, OffsetDateTime now) {
        User user = subscription.getUser();
        PlanVersion version = subscription.getPlanVersion();
        Plan plan = version.getPlan();
        return new SubscriptionDTO(
                subscription.getId(), user.getId(), user.getName(), user.getEmail(), user.getRole(), user.isSuspended(),
                plan.getId(), plan.getCode(), plan.getName(), version.getId(), version.getVersion(),
                version.getMonthlyPrice(), version.getCurrency(), version.getMaxPensions(), version.getMaxCollaborators(),
                version.getMaxPhotos(), version.getMaxVideos(), version.getFeaturedDays(), version.isAdvancedAnalytics(),
                version.isInquiryHistory(), version.isConsolidatedAnalytics(), version.isExportEnabled(),
                subscription.getStatus(), effectiveStatus(subscription, now), subscription.getStartedAt(), subscription.getExpiresAt(),
                subscription.getSource(), subscription.getProvider(), subscription.getProviderSubscriptionId(),
                subscription.getCancelledAt(), subscription.getCancellationReason(), subscription.getCreatedAt(), subscription.getUpdatedAt()
        );
    }

    private Map<String, Object> snapshot(OwnerSubscription subscription, OffsetDateTime now) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", subscription.getId());
        result.put("userId", subscription.getUser().getId());
        result.put("planId", subscription.getPlanVersion().getPlan().getId());
        result.put("planCode", subscription.getPlanVersion().getPlan().getCode());
        result.put("planVersionId", subscription.getPlanVersion().getId());
        result.put("planVersion", subscription.getPlanVersion().getVersion());
        result.put("status", subscription.getStatus());
        result.put("effectiveStatus", effectiveStatus(subscription, now));
        result.put("startedAt", subscription.getStartedAt());
        result.put("expiresAt", subscription.getExpiresAt());
        result.put("source", subscription.getSource());
        result.put("provider", subscription.getProvider());
        result.put("cancelledAt", subscription.getCancelledAt());
        return result;
    }

    private User requireMarketplaceUser(User user) {
        if (user == null || (user.getRole() != UserRole.SEEKER && user.getRole() != UserRole.OWNER)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuario del marketplace no encontrado");
        }
        return user;
    }

    private void validateDuration(int days) {
        if (days < 1 || days > MAX_GRANT_DAYS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La duración debe estar entre 1 y " + MAX_GRANT_DAYS + " días");
        }
    }

    private Long requireId(Long id) {
        if (id == null || id <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El identificador no es válido");
        }
        return id;
    }

    private String clean(String value, int max, String label) {
        if (value == null || value.isBlank()) return null;
        String result = value.trim();
        if (result.length() > max) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + " no puede superar " + max + " caracteres");
        }
        return result;
    }

    private String shortReason(String reason) {
        return reason.length() <= 500 ? reason : reason.substring(0, 500);
    }

    private long safe(Long value) {
        return value == null ? 0L : Math.max(0L, value);
    }

    public record SubscriptionSummaryDTO(long total, long active, long expired, long cancelled, long adminGrants) {}

    public record SubscriptionDTO(
            Long id,
            Long userId,
            String userName,
            String userEmail,
            UserRole userRole,
            boolean userSuspended,
            Long planId,
            String planCode,
            String planName,
            Long planVersionId,
            int planVersion,
            java.math.BigDecimal monthlyPrice,
            String currency,
            Integer maxPensions,
            Integer maxCollaborators,
            Integer maxPhotos,
            Integer maxVideos,
            int featuredDays,
            boolean advancedAnalytics,
            boolean inquiryHistory,
            boolean consolidatedAnalytics,
            boolean exportEnabled,
            SubscriptionStatus status,
            SubscriptionStatus effectiveStatus,
            OffsetDateTime startedAt,
            OffsetDateTime expiresAt,
            SubscriptionSource source,
            String provider,
            String providerSubscriptionId,
            OffsetDateTime cancelledAt,
            String cancellationReason,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {}
}
