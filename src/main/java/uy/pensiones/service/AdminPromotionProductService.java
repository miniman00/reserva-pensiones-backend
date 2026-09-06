package uy.pensiones.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import uy.pensiones.config.AppProperties;
import uy.pensiones.enums.AdminAuditAction;
import uy.pensiones.enums.AdminAuditEntityType;
import uy.pensiones.enums.PromotionProductVersionStatus;
import uy.pensiones.enums.PromotionTargetType;
import uy.pensiones.model.BackofficeUser;
import uy.pensiones.model.PromotionProduct;
import uy.pensiones.model.PromotionProductVersion;
import uy.pensiones.repo.PromotionProductRepository;
import uy.pensiones.repo.PromotionProductVersionRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class AdminPromotionProductService {

    private static final int MAX_DURATION_DAYS = 3_650;

    private final PromotionProductRepository products;
    private final PromotionProductVersionRepository versions;
    private final AdminAuditService audit;
    private final AppProperties properties;

    public AdminPromotionProductService(PromotionProductRepository products,
                                        PromotionProductVersionRepository versions,
                                        AdminAuditService audit,
                                        AppProperties properties) {
        this.products = products;
        this.versions = versions;
        this.audit = audit;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public List<PromotionProductDTO> list() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return products.findAllByOrderByTargetTypeAscDurationDaysAscNameAsc().stream()
                .map(product -> dto(product, versions.findByProductIdOrderByVersionDesc(product.getId()), now))
                .toList();
    }

    @Transactional(readOnly = true)
    public PromotionProductDTO detail(Long productId) {
        PromotionProduct product = findProduct(productId);
        return dto(product, versions.findByProductIdOrderByVersionDesc(productId), OffsetDateTime.now(ZoneOffset.UTC));
    }

    @Transactional
    public PromotionProductDTO createProduct(String rawCode,
                                             String rawName,
                                             String rawDescription,
                                             PromotionTargetType targetType,
                                             Integer durationDays,
                                             BackofficeUser actor) {
        String code = cleanCode(rawCode);
        String name = cleanRequired(rawName, "El nombre del producto", 120);
        String description = cleanOptional(rawDescription, "La descripción", 700);
        if (targetType == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El tipo de targeting es obligatorio");
        }
        if (targetType != PromotionTargetType.GLOBAL && targetType != PromotionTargetType.STUDY_CENTER) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Por ahora solo están habilitados los destacados GLOBAL y STUDY_CENTER. Departamento y zona quedan reservados para una fase posterior");
        }
        int duration = validateDuration(durationDays);
        if (products.findByCodeIgnoreCase(code).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ya existe un producto de destacado con ese código");
        }

        PromotionProduct product = products.save(PromotionProduct.builder()
                .code(code)
                .name(name)
                .description(description)
                .targetType(targetType)
                .durationDays(duration)
                // Los productos personalizados nacen inactivos; requieren activación explícita.
                .active(false)
                .build());
        audit.record(actor, AdminAuditAction.ADMIN_CREATE_PROMOTION_PRODUCT,
                AdminAuditEntityType.PROMOTION_PRODUCT, product.getId(), null, productSnapshot(product), null);
        return dto(product, List.of(), OffsetDateTime.now(ZoneOffset.UTC));
    }

    @Transactional
    public PromotionProductDTO updateProduct(Long productId,
                                             String rawName,
                                             String rawDescription,
                                             String rawReason,
                                             BackofficeUser actor) {
        PromotionProduct product = lockProduct(productId);
        String reason = audit.requireReason(rawReason);
        Map<String, Object> before = productSnapshot(product);
        product.setName(cleanRequired(rawName, "El nombre del producto", 120));
        product.setDescription(cleanOptional(rawDescription, "La descripción", 700));
        product = products.save(product);
        audit.record(actor, AdminAuditAction.ADMIN_UPDATE_PROMOTION_PRODUCT,
                AdminAuditEntityType.PROMOTION_PRODUCT, product.getId(), before, productSnapshot(product), reason);
        return dto(product, versions.findByProductIdOrderByVersionDesc(productId), OffsetDateTime.now(ZoneOffset.UTC));
    }

    @Transactional
    public PromotionProductDTO setProductActive(Long productId,
                                                boolean active,
                                                String rawReason,
                                                BackofficeUser actor) {
        PromotionProduct product = lockProduct(productId);
        if (product.isActive() == active) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    active ? "El producto ya está activo" : "El producto ya está inactivo");
        }
        String reason = audit.requireReason(rawReason);
        Map<String, Object> before = productSnapshot(product);
        product.setActive(active);
        product = products.save(product);
        audit.record(actor,
                active ? AdminAuditAction.ADMIN_ACTIVATE_PROMOTION_PRODUCT
                        : AdminAuditAction.ADMIN_DEACTIVATE_PROMOTION_PRODUCT,
                AdminAuditEntityType.PROMOTION_PRODUCT, product.getId(), before, productSnapshot(product), reason);
        return dto(product, versions.findByProductIdOrderByVersionDesc(productId), OffsetDateTime.now(ZoneOffset.UTC));
    }

    @Transactional
    public PromotionProductVersionDTO createDraft(Long productId, VersionInput input, BackofficeUser actor) {
        PromotionProduct product = lockProduct(productId);
        ValidatedVersion data = validateVersion(input);
        int nextVersion = versions.findTopByProductIdOrderByVersionDesc(productId)
                .map(previous -> previous.getVersion() + 1)
                .orElse(1);

        PromotionProductVersion version = new PromotionProductVersion();
        version.setProduct(product);
        version.setVersion(nextVersion);
        apply(version, data);
        version.setStatus(PromotionProductVersionStatus.DRAFT);
        version = versions.save(version);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        audit.record(actor, AdminAuditAction.ADMIN_CREATE_PROMOTION_PRODUCT_VERSION,
                AdminAuditEntityType.PROMOTION_PRODUCT_VERSION, version.getId(), null,
                versionSnapshot(version, now), null);
        return versionDto(version, now);
    }

    @Transactional
    public PromotionProductVersionDTO updateDraft(Long versionId, VersionInput input, BackofficeUser actor) {
        Long productId = findVersionProductId(versionId);
        lockProduct(productId);
        PromotionProductVersion version = findVersion(versionId);
        if (version.getStatus() != PromotionProductVersionStatus.DRAFT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Solo los precios en borrador pueden editarse. Crea una nueva versión para cambiar una publicada");
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Map<String, Object> before = versionSnapshot(version, now);
        apply(version, validateVersion(input));
        version = versions.save(version);
        audit.record(actor, AdminAuditAction.ADMIN_UPDATE_PROMOTION_PRODUCT_VERSION,
                AdminAuditEntityType.PROMOTION_PRODUCT_VERSION, version.getId(), before,
                versionSnapshot(version, now), null);
        return versionDto(version, now);
    }

    @Transactional
    public PromotionProductVersionDTO publish(Long versionId, String rawReason, BackofficeUser actor) {
        Long productId = findVersionProductId(versionId);
        lockProduct(productId);
        PromotionProductVersion version = findVersion(versionId);
        if (version.getStatus() != PromotionProductVersionStatus.DRAFT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "La versión de precio ya no está en borrador");
        }
        if (version.getEffectiveFrom() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Debes indicar desde cuándo entra en vigencia antes de publicar el precio");
        }
        String reason = audit.requireReason(rawReason);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (version.getEffectiveFrom().isBefore(now.minusMinutes(5))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "No se puede publicar un precio con vigencia retroactiva. Programa una fecha actual o futura");
        }
        if (version.getEffectiveFrom().isBefore(now)) {
            version.setEffectiveFrom(now);
        }
        validateDates(version.getEffectiveFrom(), version.getEffectiveUntil());

        List<PromotionProductVersion> published = versions.findByProductIdAndStatusOrderByEffectiveFromAsc(
                productId, PromotionProductVersionStatus.PUBLISHED);
        for (PromotionProductVersion other : published) {
            if (other.getEffectiveFrom() != null && !other.getEffectiveFrom().isBefore(version.getEffectiveFrom())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Ya existe un precio publicado con vigencia igual o posterior. Retíralo o programa esta versión después");
            }
        }

        PromotionProductVersion predecessor = published.isEmpty() ? null : published.get(published.size() - 1);
        Map<String, Object> predecessorBefore = null;
        if (predecessor != null
                && (predecessor.getEffectiveUntil() == null
                    || predecessor.getEffectiveUntil().isAfter(version.getEffectiveFrom()))) {
            predecessorBefore = versionSnapshot(predecessor, now);
            predecessor.setEffectiveUntil(version.getEffectiveFrom());
            versions.save(predecessor);
        }

        Map<String, Object> before = versionSnapshot(version, now);
        version.setStatus(PromotionProductVersionStatus.PUBLISHED);
        version.setPublishedAt(now);
        version.setRetiredAt(null);
        version = versions.save(version);

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("publishedVersion", versionSnapshot(version, now));
        if (predecessor != null && predecessorBefore != null) {
            after.put("previousVersionBefore", predecessorBefore);
            after.put("previousVersionAfter", versionSnapshot(predecessor, now));
        }
        audit.record(actor, AdminAuditAction.ADMIN_PUBLISH_PROMOTION_PRODUCT_VERSION,
                AdminAuditEntityType.PROMOTION_PRODUCT_VERSION, version.getId(), before, after, reason);
        return versionDto(version, now);
    }

    @Transactional
    public PromotionProductVersionDTO retire(Long versionId, String rawReason, BackofficeUser actor) {
        Long productId = findVersionProductId(versionId);
        lockProduct(productId);
        PromotionProductVersion version = findVersion(versionId);
        if (version.getStatus() != PromotionProductVersionStatus.PUBLISHED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Solo un precio publicado puede retirarse");
        }
        String reason = audit.requireReason(rawReason);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Map<String, Object> before = versionSnapshot(version, now);
        version.setStatus(PromotionProductVersionStatus.RETIRED);
        version.setRetiredAt(now);
        if (version.getEffectiveFrom() != null
                && !now.isBefore(version.getEffectiveFrom())
                && (version.getEffectiveUntil() == null || version.getEffectiveUntil().isAfter(now))) {
            version.setEffectiveUntil(now);
        }
        version = versions.save(version);
        audit.record(actor, AdminAuditAction.ADMIN_RETIRE_PROMOTION_PRODUCT_VERSION,
                AdminAuditEntityType.PROMOTION_PRODUCT_VERSION, version.getId(), before,
                versionSnapshot(version, now), reason);
        return versionDto(version, now);
    }

    private PromotionProductDTO dto(PromotionProduct product,
                                    List<PromotionProductVersion> items,
                                    OffsetDateTime now) {
        List<PromotionProductVersionDTO> versionDtos = items.stream().map(item -> versionDto(item, now)).toList();
        PromotionProductVersionDTO current = versionDtos.stream()
                .filter(item -> item.effectiveState() == EffectiveState.ACTIVE)
                .findFirst().orElse(null);
        PromotionProductVersionDTO scheduled = versionDtos.stream()
                .filter(item -> item.effectiveState() == EffectiveState.SCHEDULED)
                .min(Comparator.comparing(PromotionProductVersionDTO::effectiveFrom))
                .orElse(null);
        boolean eligibleForOffer = properties.getMonetization().isEnabled() && product.isActive() && current != null;
        return new PromotionProductDTO(product.getId(), product.getCode(), product.getName(), product.getDescription(),
                product.getTargetType(), product.getDurationDays(), product.isActive(), eligibleForOffer,
                product.getCreatedAt(), product.getUpdatedAt(), current, scheduled, versionDtos);
    }

    private PromotionProductVersionDTO versionDto(PromotionProductVersion version, OffsetDateTime now) {
        return new PromotionProductVersionDTO(
                version.getId(), version.getProduct().getId(), version.getVersion(), version.getPrice(),
                version.getCurrency(), version.getEffectiveFrom(), version.getEffectiveUntil(), version.getStatus(),
                effectiveState(version, now), version.getPublishedAt(), version.getRetiredAt(),
                version.getCreatedAt(), version.getUpdatedAt()
        );
    }

    private EffectiveState effectiveState(PromotionProductVersion version, OffsetDateTime now) {
        if (version.getStatus() == PromotionProductVersionStatus.DRAFT) return EffectiveState.DRAFT;
        if (version.getStatus() == PromotionProductVersionStatus.RETIRED) return EffectiveState.RETIRED;
        if (version.getEffectiveFrom() == null) return EffectiveState.DRAFT;
        if (now.isBefore(version.getEffectiveFrom())) return EffectiveState.SCHEDULED;
        if (version.getEffectiveUntil() != null && !now.isBefore(version.getEffectiveUntil())) return EffectiveState.EXPIRED;
        return EffectiveState.ACTIVE;
    }

    private Map<String, Object> productSnapshot(PromotionProduct product) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", product.getId());
        result.put("code", product.getCode());
        result.put("name", product.getName());
        result.put("description", product.getDescription());
        result.put("targetType", product.getTargetType());
        result.put("durationDays", product.getDurationDays());
        result.put("active", product.isActive());
        return result;
    }

    private Map<String, Object> versionSnapshot(PromotionProductVersion version, OffsetDateTime now) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", version.getId());
        result.put("promotionProductId", version.getProduct().getId());
        result.put("version", version.getVersion());
        result.put("price", version.getPrice());
        result.put("currency", version.getCurrency());
        result.put("effectiveFrom", version.getEffectiveFrom());
        result.put("effectiveUntil", version.getEffectiveUntil());
        result.put("status", version.getStatus());
        result.put("effectiveState", effectiveState(version, now));
        return result;
    }

    private ValidatedVersion validateVersion(VersionInput input) {
        if (input == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Los datos del precio son obligatorios");
        }
        BigDecimal price = input.price() == null ? null : input.price().stripTrailingZeros();
        if (price == null || price.signum() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El precio no puede ser negativo");
        }
        if (price.scale() > 2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El precio admite como máximo 2 decimales");
        }
        if (price.compareTo(new BigDecimal("9999999999.99")) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El precio es demasiado alto");
        }
        price = price.setScale(2, RoundingMode.UNNECESSARY);
        String currency = validateCurrency(input.currency());
        validateDates(input.effectiveFrom(), input.effectiveUntil());
        return new ValidatedVersion(price, currency, input.effectiveFrom(), input.effectiveUntil());
    }

    private void apply(PromotionProductVersion version, ValidatedVersion data) {
        version.setPrice(data.price());
        version.setCurrency(data.currency());
        version.setEffectiveFrom(data.effectiveFrom());
        version.setEffectiveUntil(data.effectiveUntil());
    }

    private int validateDuration(Integer value) {
        if (value == null || value < 1 || value > MAX_DURATION_DAYS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La duración debe estar entre 1 y " + MAX_DURATION_DAYS + " días");
        }
        return value;
    }

    private void validateDates(OffsetDateTime from, OffsetDateTime until) {
        if (from != null && until != null && !until.isAfter(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La fecha final de vigencia debe ser posterior a la fecha inicial");
        }
    }

    private String validateCurrency(String raw) {
        String value = cleanRequired(raw, "La moneda", 3).toUpperCase(Locale.ROOT);
        if (value.length() != 3) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La moneda debe usar un código ISO 4217 de 3 letras");
        }
        try {
            Currency.getInstance(value);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El código de moneda no es válido");
        }
        return value;
    }

    private String cleanCode(String raw) {
        String code = cleanRequired(raw, "El código del producto", 50).toUpperCase(Locale.ROOT);
        if (!code.matches("[A-Z][A-Z0-9_]{1,49}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El código debe comenzar con una letra y contener solo A-Z, 0-9 o _");
        }
        return code;
    }

    private String cleanRequired(String raw, String label, int max) {
        String value = cleanOptional(raw, label, max);
        if (value == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + " es obligatorio");
        return value;
    }

    private String cleanOptional(String raw, String label, int max) {
        if (raw == null || raw.isBlank()) return null;
        String value = raw.trim();
        if (value.length() > max) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    label + " no puede superar " + max + " caracteres");
        }
        return value;
    }

    private PromotionProduct findProduct(Long id) {
        if (id == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El producto es obligatorio");
        return products.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Producto de destacado no encontrado"));
    }

    private PromotionProduct lockProduct(Long id) {
        if (id == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El producto es obligatorio");
        return products.findByIdForUpdate(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Producto de destacado no encontrado"));
    }

    private Long findVersionProductId(Long id) {
        if (id == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La versión es obligatoria");
        return versions.findProductIdByVersionId(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Versión de precio no encontrada"));
    }

    private PromotionProductVersion findVersion(Long id) {
        if (id == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La versión es obligatoria");
        return versions.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Versión de precio no encontrada"));
    }

    public enum EffectiveState { DRAFT, SCHEDULED, ACTIVE, EXPIRED, RETIRED }

    public record PromotionProductDTO(
            Long id,
            String code,
            String name,
            String description,
            PromotionTargetType targetType,
            int durationDays,
            boolean active,
            boolean eligibleForOffer,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            PromotionProductVersionDTO currentVersion,
            PromotionProductVersionDTO nextScheduledVersion,
            List<PromotionProductVersionDTO> versions
    ) {}

    public record PromotionProductVersionDTO(
            Long id,
            Long promotionProductId,
            int version,
            BigDecimal price,
            String currency,
            OffsetDateTime effectiveFrom,
            OffsetDateTime effectiveUntil,
            PromotionProductVersionStatus status,
            EffectiveState effectiveState,
            OffsetDateTime publishedAt,
            OffsetDateTime retiredAt,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {}

    public record VersionInput(
            BigDecimal price,
            String currency,
            OffsetDateTime effectiveFrom,
            OffsetDateTime effectiveUntil
    ) {}

    private record ValidatedVersion(
            BigDecimal price,
            String currency,
            OffsetDateTime effectiveFrom,
            OffsetDateTime effectiveUntil
    ) {}
}
