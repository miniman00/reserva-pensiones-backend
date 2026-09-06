package uy.pensiones.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import uy.pensiones.enums.AdminAuditAction;
import uy.pensiones.enums.AdminAuditEntityType;
import uy.pensiones.enums.PensionPromotionEffectiveStatus;
import uy.pensiones.enums.PensionPromotionSource;
import uy.pensiones.enums.PensionPromotionStatus;
import uy.pensiones.enums.PensionPromotionType;
import uy.pensiones.enums.PensionStatus;
import uy.pensiones.enums.PromotionProductVersionStatus;
import uy.pensiones.enums.PromotionTargetType;
import uy.pensiones.model.BackofficeUser;
import uy.pensiones.model.Pension;
import uy.pensiones.model.PensionPromotion;
import uy.pensiones.model.PromotionProduct;
import uy.pensiones.model.PromotionProductVersion;
import uy.pensiones.model.StudyCenterCatalog;
import uy.pensiones.model.User;
import uy.pensiones.repo.PensionPromotionRepository;
import uy.pensiones.repo.PensionRepository;
import uy.pensiones.repo.PromotionProductVersionRepository;
import uy.pensiones.repo.StudyCenterCatalogRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class AdminPensionPromotionService {

    private static final int MAX_FILTER_LENGTH = 180;

    private final PensionPromotionRepository promotions;
    private final PensionRepository pensions;
    private final PromotionProductVersionRepository productVersions;
    private final StudyCenterCatalogRepository studyCenters;
    private final AdminAuditService audit;

    public AdminPensionPromotionService(PensionPromotionRepository promotions,
                                        PensionRepository pensions,
                                        PromotionProductVersionRepository productVersions,
                                        StudyCenterCatalogRepository studyCenters,
                                        AdminAuditService audit) {
        this.promotions = promotions;
        this.pensions = pensions;
        this.productVersions = productVersions;
        this.studyCenters = studyCenters;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public Page<PromotionSummaryDTO> list(String q,
                                          Long pensionId,
                                          PromotionTargetType targetType,
                                          PensionPromotionSource source,
                                          String productCode,
                                          Long studyCenterId,
                                          PensionPromotionEffectiveStatus status,
                                          int page,
                                          int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(100, Math.max(1, size));
        return promotions.adminList(
                cleanFilter(q, "búsqueda"),
                positiveOrNull(pensionId, "pensión"),
                targetType == null ? "" : targetType.name(),
                source == null ? "" : source.name(),
                cleanCodeFilter(productCode),
                positiveOrNull(studyCenterId, "centro de estudio"),
                status == null ? "" : status.name(),
                PageRequest.of(safePage, safeSize)
        ).map(this::rowDto);
    }

    @Transactional(readOnly = true)
    public PromotionDashboardDTO summary() {
        var row = promotions.summary();
        return new PromotionDashboardDTO(
                safe(row == null ? null : row.getTotal()),
                safe(row == null ? null : row.getActive()),
                safe(row == null ? null : row.getScheduled()),
                safe(row == null ? null : row.getExpiringWithin7Days()),
                safe(row == null ? null : row.getAdminGrants()),
                safe(row == null ? null : row.getStudyCenterPromotions()),
                safe(row == null ? null : row.getLegacyPromotions())
        );
    }

    @Transactional(readOnly = true)
    public PromotionDetailDTO detail(Long promotionId) {
        return detailDto(findDetail(promotionId), OffsetDateTime.now(ZoneOffset.UTC));
    }

    @Transactional
    public PromotionDetailDTO grant(GrantInput input, BackofficeUser actor) {
        if (input == null || input.pensionId() == null || input.productVersionId() == null) {
            throw badRequest("Debes indicar la pensión y la versión del producto de destacado");
        }
        String reason = audit.requireReason(input.reason());
        Pension pension = pensions.findByIdForEntitlementUpdate(input.pensionId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pensión no encontrada"));
        validatePensionForGrant(pension);

        PromotionProductVersion productVersion = productVersions.findById(input.productVersionId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Versión del producto de destacado no encontrada"));
        PromotionProduct product = productVersion.getProduct();

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime startsAt = normalizeStart(input.startsAt(), now);
        validateProductVersion(productVersion, product, startsAt);
        OffsetDateTime endsAt = startsAt.plusDays(product.getDurationDays());

        StudyCenterCatalog center = resolveTarget(product, input.studyCenterId());
        Long centerId = center == null ? null : center.getId();
        long overlapping = promotions.countOverlapping(
                pension.getId(), product.getTargetType().name(), centerId, startsAt, endsAt);
        if (overlapping > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    product.getTargetType() == PromotionTargetType.GLOBAL
                            ? "La pensión ya tiene un destacado general que se solapa con esa vigencia"
                            : "La pensión ya tiene un destacado para ese centro de estudio que se solapa con esa vigencia");
        }

        PensionPromotion promotion = promotions.save(PensionPromotion.builder()
                .pension(pension)
                .productVersion(productVersion)
                .type(PensionPromotionType.FEATURED)
                .targetType(product.getTargetType())
                .studyCenter(center)
                .targetValue(null)
                .startsAt(startsAt)
                .endsAt(endsAt)
                .status(PensionPromotionStatus.ACTIVE)
                .price(BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY))
                .currency(productVersion.getCurrency())
                .payment(null)
                .source(PensionPromotionSource.ADMIN_GRANT)
                .createdByBackoffice(actor)
                .build());

        Map<String, Object> after = snapshot(promotion, now);
        after.put("catalogPrice", productVersion.getPrice());
        after.put("grantReason", reason);
        audit.record(actor, AdminAuditAction.ADMIN_FEATURE_PENSION, AdminAuditEntityType.PROMOTION,
                promotion.getId(), null, after, reason);
        return detailDto(promotion, now);
    }

    @Transactional
    public PromotionDetailDTO cancel(Long promotionId, String rawReason, BackofficeUser actor) {
        if (promotionId == null || promotionId <= 0) throw badRequest("Identificador de promoción inválido");
        String reason = audit.requireReason(rawReason);

        PensionPromotion first = promotions.findAdminDetailById(promotionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Promoción no encontrada"));
        Pension lockedPension = pensions.findByIdForEntitlementUpdate(first.getPension().getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pensión no encontrada"));
        PensionPromotion promotion = promotions.findByIdForUpdate(promotionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Promoción no encontrada"));

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        PensionPromotionEffectiveStatus effective = effectiveStatus(promotion, now);
        if (effective == PensionPromotionEffectiveStatus.CANCELLED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "La promoción ya está cancelada");
        }
        if (effective == PensionPromotionEffectiveStatus.EXPIRED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Una promoción vencida ya no necesita cancelación");
        }
        if (promotion.getSource() == PensionPromotionSource.PAYMENT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Las promociones pagas deberán cancelarse mediante PaymentGateway desde el módulo Pagos y conciliación");
        }

        Map<String, Object> before = snapshot(promotion, now);
        promotion.setStatus(PensionPromotionStatus.CANCELLED);
        promotion.setCancelledAt(now);
        promotion.setCancellationReason(reason);
        promotion = promotions.save(promotion);

        // Un destacado legado sí alimenta hoy el booleano consumido por el portal. Al cancelarlo,
        // mantenemos ambas representaciones compatibles. Los grants nuevos no modifican ese booleano.
        if (promotion.getSource() == PensionPromotionSource.LEGACY_COMPATIBILITY
                && Boolean.TRUE.equals(lockedPension.getFeatured())) {
            lockedPension.setFeatured(false);
            pensions.save(lockedPension);
        }

        audit.record(actor, AdminAuditAction.ADMIN_UNFEATURE_PENSION, AdminAuditEntityType.PROMOTION,
                promotion.getId(), before, snapshot(promotion, now), reason);
        return detailDto(promotion, now);
    }

    private void validatePensionForGrant(Pension pension) {
        if (pension.getStatus() != PensionStatus.PUBLISHED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Solo una pensión publicada puede recibir un destacado");
        }
        if (Boolean.TRUE.equals(pension.getModerationBlocked())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "No se puede destacar una pensión bloqueada por moderación");
        }
        User owner = responsibleOwner(pension);
        if (owner == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "La pensión no tiene un responsable válido");
        }
        if (owner.isSuspended()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "No se puede destacar una pensión cuyo propietario está suspendido");
        }
    }

    private void validateProductVersion(PromotionProductVersion version,
                                        PromotionProduct product,
                                        OffsetDateTime startsAt) {
        if (version.getStatus() != PromotionProductVersionStatus.PUBLISHED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Solo puede utilizarse una versión de precio publicada");
        }
        if (product == null || !product.isActive()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "El producto de destacado está inactivo");
        }
        if (product.getTargetType() != PromotionTargetType.GLOBAL
                && product.getTargetType() != PromotionTargetType.STUDY_CENTER) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Ese tipo de targeting todavía no está habilitado para promociones activas");
        }
        if (version.getEffectiveFrom() == null || startsAt.isBefore(version.getEffectiveFrom())
                || (version.getEffectiveUntil() != null && !startsAt.isBefore(version.getEffectiveUntil()))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "La versión de precio no está vigente en la fecha de inicio seleccionada");
        }
    }

    private StudyCenterCatalog resolveTarget(PromotionProduct product, Long studyCenterId) {
        if (product.getTargetType() == PromotionTargetType.GLOBAL) {
            if (studyCenterId != null) throw badRequest("Un destacado GLOBAL no debe indicar centro de estudio");
            return null;
        }
        if (studyCenterId == null || studyCenterId <= 0) {
            throw badRequest("Debes seleccionar el centro de estudio del destacado");
        }
        StudyCenterCatalog center = studyCenters.findById(studyCenterId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Centro de estudio no encontrado"));
        if (!Boolean.TRUE.equals(center.getActive()) || !Boolean.TRUE.equals(center.getVerified())
                || center.getLat() == null || center.getLng() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "El centro debe estar activo, verificado y geolocalizado para recibir promociones dirigidas");
        }
        return center;
    }

    private OffsetDateTime normalizeStart(OffsetDateTime requested, OffsetDateTime now) {
        OffsetDateTime value = requested == null ? now : requested.withOffsetSameInstant(ZoneOffset.UTC);
        if (value.isBefore(now.minusMinutes(5))) {
            throw badRequest("No se puede otorgar una promoción con inicio retroactivo");
        }
        return value.isBefore(now) ? now : value;
    }

    private PensionPromotion findDetail(Long id) {
        if (id == null || id <= 0) throw badRequest("Identificador de promoción inválido");
        return promotions.findAdminDetailById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Promoción no encontrada"));
    }

    private PromotionSummaryDTO rowDto(PensionPromotionRepository.AdminPromotionRow row) {
        return new PromotionSummaryDTO(
                row.getId(), row.getPensionId(), row.getPensionName(), row.getPensionStatus(),
                Boolean.TRUE.equals(row.getPensionBlocked()), row.getOwnerId(), row.getOwnerName(), row.getOwnerEmail(),
                Boolean.TRUE.equals(row.getOwnerSuspended()), PromotionTargetType.valueOf(row.getTargetType()),
                row.getStudyCenterId(), row.getStudyCenterName(), row.getProductCode(), row.getProductName(),
                row.getProductVersionId(), row.getProductVersion(), row.getCatalogPrice(), row.getPrice(), row.getCurrency(),
                PensionPromotionSource.valueOf(row.getSource()), PensionPromotionStatus.valueOf(row.getStoredStatus()),
                PensionPromotionEffectiveStatus.valueOf(row.getEffectiveStatus()), row.getStartsAt(), row.getEndsAt(),
                row.getCancelledAt(), row.getCancellationReason(), row.getCreatedByDisplayName(), row.getCreatedAt()
        );
    }

    private PromotionDetailDTO detailDto(PensionPromotion promotion, OffsetDateTime now) {
        Pension pension = promotion.getPension();
        User owner = responsibleOwner(pension);
        PromotionProductVersion version = promotion.getProductVersion();
        PromotionProduct product = version == null ? null : version.getProduct();
        StudyCenterCatalog center = promotion.getStudyCenter();
        return new PromotionDetailDTO(
                promotion.getId(), promotion.getType(), promotion.getTargetType(),
                effectiveStatus(promotion, now), promotion.getStatus(), promotion.getSource(),
                promotion.getStartsAt(), promotion.getEndsAt(), promotion.getPrice(), promotion.getCurrency(),
                promotion.getPayment() == null ? null : promotion.getPayment().getId(), promotion.getCancelledAt(), promotion.getCancellationReason(),
                promotion.getCreatedAt(), promotion.getUpdatedAt(),
                new PromotionPensionDTO(pension.getId(), pension.getName(), pension.getStatus(),
                        Boolean.TRUE.equals(pension.getModerationBlocked()), Boolean.TRUE.equals(pension.getFeatured())),
                owner == null ? null : new PromotionOwnerDTO(owner.getId(), owner.getName(), owner.getEmail(), owner.isSuspended()),
                product == null ? null : new PromotionProductDTO(product.getId(), product.getCode(), product.getName(),
                        product.getTargetType(), product.getDurationDays(), product.isActive()),
                version == null ? null : new PromotionPriceVersionDTO(version.getId(), version.getVersion(),
                        version.getPrice(), version.getCurrency(), version.getEffectiveFrom(), version.getEffectiveUntil(),
                        version.getStatus()),
                center == null ? null : new PromotionStudyCenterDTO(center.getId(), center.getName(), center.getCity(),
                        center.getCountryCode(), Boolean.TRUE.equals(center.getActive()), Boolean.TRUE.equals(center.getVerified()),
                        center.getLat(), center.getLng()),
                promotion.getCreatedByBackoffice() == null ? null : promotion.getCreatedByBackoffice().getDisplayName(),
                promotion.getCreatedByBackoffice() == null ? null : promotion.getCreatedByBackoffice().getUsername(),
                promotion.getSource() == PensionPromotionSource.LEGACY_COMPATIBILITY
        );
    }

    public PensionPromotionEffectiveStatus effectiveStatus(PensionPromotion promotion, OffsetDateTime now) {
        if (promotion.getStatus() == PensionPromotionStatus.CANCELLED) return PensionPromotionEffectiveStatus.CANCELLED;
        if (promotion.getStartsAt() != null && promotion.getStartsAt().isAfter(now)) return PensionPromotionEffectiveStatus.SCHEDULED;
        if (promotion.getEndsAt() != null && !promotion.getEndsAt().isAfter(now)) return PensionPromotionEffectiveStatus.EXPIRED;
        return PensionPromotionEffectiveStatus.ACTIVE;
    }

    private Map<String, Object> snapshot(PensionPromotion promotion, OffsetDateTime now) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", promotion.getId());
        out.put("pensionId", promotion.getPension() == null ? null : promotion.getPension().getId());
        out.put("promotionProductVersionId", promotion.getProductVersion() == null ? null : promotion.getProductVersion().getId());
        out.put("targetType", promotion.getTargetType());
        out.put("studyCenterId", promotion.getStudyCenter() == null ? null : promotion.getStudyCenter().getId());
        out.put("startsAt", promotion.getStartsAt());
        out.put("endsAt", promotion.getEndsAt());
        out.put("status", promotion.getStatus());
        out.put("effectiveStatus", effectiveStatus(promotion, now));
        out.put("price", promotion.getPrice());
        out.put("currency", promotion.getCurrency());
        out.put("source", promotion.getSource());
        out.put("paymentId", promotion.getPayment() == null ? null : promotion.getPayment().getId());
        out.put("cancelledAt", promotion.getCancelledAt());
        out.put("cancellationReason", promotion.getCancellationReason());
        return out;
    }

    private User responsibleOwner(Pension pension) {
        return pension.getOwner() != null ? pension.getOwner() : pension.getCreatedBy();
    }

    private String cleanFilter(String raw, String label) {
        if (raw == null || raw.isBlank()) return "";
        String value = raw.trim();
        if (value.length() > MAX_FILTER_LENGTH) throw badRequest("El filtro de " + label + " es demasiado largo");
        return value;
    }

    private String cleanCodeFilter(String raw) {
        String value = cleanFilter(raw, "producto");
        return value.toUpperCase(java.util.Locale.ROOT);
    }

    private Long positiveOrNull(Long value, String label) {
        if (value == null) return null;
        if (value <= 0) throw badRequest("El identificador de " + label + " no es válido");
        return value;
    }

    private long safe(Long value) { return value == null ? 0L : Math.max(0L, value); }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    public record GrantInput(Long pensionId,
                             Long productVersionId,
                             Long studyCenterId,
                             OffsetDateTime startsAt,
                             String reason) {}

    public record PromotionSummaryDTO(
            Long id,
            Long pensionId,
            String pensionName,
            String pensionStatus,
            boolean pensionBlocked,
            Long ownerId,
            String ownerName,
            String ownerEmail,
            boolean ownerSuspended,
            PromotionTargetType targetType,
            Long studyCenterId,
            String studyCenterName,
            String productCode,
            String productName,
            Long productVersionId,
            Integer productVersion,
            BigDecimal catalogPrice,
            BigDecimal price,
            String currency,
            PensionPromotionSource source,
            PensionPromotionStatus storedStatus,
            PensionPromotionEffectiveStatus effectiveStatus,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            OffsetDateTime cancelledAt,
            String cancellationReason,
            String createdByDisplayName,
            OffsetDateTime createdAt
    ) {}

    public record PromotionDashboardDTO(long total,
                                        long active,
                                        long scheduled,
                                        long expiringWithin7Days,
                                        long adminGrants,
                                        long studyCenterPromotions,
                                        long legacyPromotions) {}

    public record PromotionPensionDTO(Long id, String name, PensionStatus status, boolean moderationBlocked, boolean legacyFeatured) {}
    public record PromotionOwnerDTO(Long id, String name, String email, boolean suspended) {}
    public record PromotionProductDTO(Long id, String code, String name, PromotionTargetType targetType, int durationDays, boolean active) {}
    public record PromotionPriceVersionDTO(Long id, int version, BigDecimal catalogPrice, String currency,
                                           OffsetDateTime effectiveFrom, OffsetDateTime effectiveUntil,
                                           PromotionProductVersionStatus status) {}
    public record PromotionStudyCenterDTO(Long id, String name, String city, String countryCode,
                                          boolean active, boolean verified, Double lat, Double lng) {}

    public record PromotionDetailDTO(
            Long id,
            PensionPromotionType type,
            PromotionTargetType targetType,
            PensionPromotionEffectiveStatus effectiveStatus,
            PensionPromotionStatus storedStatus,
            PensionPromotionSource source,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            BigDecimal price,
            String currency,
            Long paymentId,
            OffsetDateTime cancelledAt,
            String cancellationReason,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            PromotionPensionDTO pension,
            PromotionOwnerDTO owner,
            PromotionProductDTO product,
            PromotionPriceVersionDTO productVersion,
            PromotionStudyCenterDTO studyCenter,
            String createdByDisplayName,
            String createdByUsername,
            boolean legacyCompatibility
    ) {}
}
