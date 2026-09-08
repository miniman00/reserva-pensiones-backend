package uy.pensiones.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import uy.pensiones.config.AppProperties;
import uy.pensiones.payment.PaymentRuntimeConfigurationService;
import uy.pensiones.enums.AdminAuditAction;
import uy.pensiones.enums.AdminAuditEntityType;
import uy.pensiones.enums.PlanVersionStatus;
import uy.pensiones.model.BackofficeUser;
import uy.pensiones.model.Plan;
import uy.pensiones.model.PlanVersion;
import uy.pensiones.model.PlanVersionPeriodPrice;
import uy.pensiones.repo.PlanRepository;
import uy.pensiones.repo.PlanVersionRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class AdminCommercialPlanService {

    private static final int MAX_LIMIT_VALUE = 100_000;
    private static final int MAX_FEATURED_DAYS = 3_650;
    private static final List<Integer> SUPPORTED_SUBSCRIPTION_PERIODS = List.of(1, 3, 6, 12);

    private final PlanRepository plans;
    private final PlanVersionRepository versions;
    private final AdminAuditService audit;
    private final AppProperties properties;
    private final PaymentRuntimeConfigurationService paymentRuntime;

    public AdminCommercialPlanService(PlanRepository plans,
                                      PlanVersionRepository versions,
                                      AdminAuditService audit,
                                      AppProperties properties,
                                      PaymentRuntimeConfigurationService paymentRuntime) {
        this.plans = plans;
        this.versions = versions;
        this.audit = audit;
        this.properties = properties;
        this.paymentRuntime = paymentRuntime;
    }

    @Transactional(readOnly = true)
    public CommercialConfigDTO config() {
        return new CommercialConfigDTO(
                properties.getMonetization().isEnabled(),
                paymentRuntime.paymentsEnabled()
        );
    }

    @Transactional(readOnly = true)
    public List<PlanDTO> list() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return plans.findAllByOrderByNameAsc().stream()
                .map(plan -> dto(plan, versions.findByPlanIdOrderByVersionDesc(plan.getId()), now))
                .toList();
    }

    @Transactional(readOnly = true)
    public PlanDTO detail(Long planId) {
        Plan plan = findPlan(planId);
        return dto(plan, versions.findByPlanIdOrderByVersionDesc(planId), OffsetDateTime.now(ZoneOffset.UTC));
    }

    @Transactional
    public PlanDTO createPlan(String rawCode, String rawName, String rawDescription, BackofficeUser actor) {
        String code = cleanCode(rawCode);
        String name = cleanRequired(rawName, "El nombre del plan", 100);
        String description = cleanOptional(rawDescription, "La descripción", 500);
        if (plans.findByCodeIgnoreCase(code).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ya existe un plan con ese código");
        }

        Plan plan = plans.save(Plan.builder()
                .code(code)
                .name(name)
                .description(description)
                // Un plan nuevo requiere activación explícita; así nunca se ofrece accidentalmente.
                .active(false)
                .build());
        audit.record(actor, AdminAuditAction.ADMIN_CREATE_PLAN, AdminAuditEntityType.PLAN,
                plan.getId(), null, planSnapshot(plan), null);
        return dto(plan, List.of(), OffsetDateTime.now(ZoneOffset.UTC));
    }

    @Transactional
    public PlanDTO updatePlan(Long planId,
                              String rawName,
                              String rawDescription,
                              String rawReason,
                              BackofficeUser actor) {
        Plan plan = lockPlan(planId);
        String reason = audit.requireReason(rawReason);
        Map<String, Object> before = planSnapshot(plan);
        plan.setName(cleanRequired(rawName, "El nombre del plan", 100));
        plan.setDescription(cleanOptional(rawDescription, "La descripción", 500));
        plan = plans.save(plan);
        audit.record(actor, AdminAuditAction.ADMIN_UPDATE_PLAN, AdminAuditEntityType.PLAN,
                plan.getId(), before, planSnapshot(plan), reason);
        return dto(plan, versions.findByPlanIdOrderByVersionDesc(planId), OffsetDateTime.now(ZoneOffset.UTC));
    }

    @Transactional
    public PlanDTO setPlanActive(Long planId, boolean active, String rawReason, BackofficeUser actor) {
        Plan plan = lockPlan(planId);
        if (plan.isActive() == active) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    active ? "El plan ya está activo" : "El plan ya está inactivo");
        }
        String reason = audit.requireReason(rawReason);
        Map<String, Object> before = planSnapshot(plan);
        plan.setActive(active);
        plan = plans.save(plan);
        audit.record(actor,
                active ? AdminAuditAction.ADMIN_ACTIVATE_PLAN : AdminAuditAction.ADMIN_DEACTIVATE_PLAN,
                AdminAuditEntityType.PLAN, plan.getId(), before, planSnapshot(plan), reason);
        return dto(plan, versions.findByPlanIdOrderByVersionDesc(planId), OffsetDateTime.now(ZoneOffset.UTC));
    }

    @Transactional
    public PlanVersionDTO createDraft(Long planId, VersionInput input, BackofficeUser actor) {
        Plan plan = lockPlan(planId);
        ValidatedVersion data = validateVersion(input);
        int nextVersion = versions.findTopByPlanIdOrderByVersionDesc(planId)
                .map(previous -> previous.getVersion() + 1)
                .orElse(1);

        PlanVersion version = new PlanVersion();
        version.setPlan(plan);
        version.setVersion(nextVersion);
        apply(version, data);
        version.setStatus(PlanVersionStatus.DRAFT);
        version = versions.save(version);
        audit.record(actor, AdminAuditAction.ADMIN_CREATE_PLAN_VERSION, AdminAuditEntityType.PLAN_VERSION,
                version.getId(), null, versionSnapshot(version, OffsetDateTime.now(ZoneOffset.UTC)), null);
        return versionDto(version, OffsetDateTime.now(ZoneOffset.UTC));
    }

    @Transactional
    public PlanVersionDTO updateDraft(Long versionId, VersionInput input, BackofficeUser actor) {
        Long planId = findVersionPlanId(versionId);
        lockPlan(planId);
        PlanVersion version = findVersion(versionId);
        if (version.getStatus() != PlanVersionStatus.DRAFT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Solo las versiones en borrador pueden editarse. Crea una nueva versión para cambiar una publicada");
        }
        Map<String, Object> before = versionSnapshot(version, OffsetDateTime.now(ZoneOffset.UTC));
        apply(version, validateVersion(input));
        version = versions.save(version);
        audit.record(actor, AdminAuditAction.ADMIN_UPDATE_PLAN_VERSION, AdminAuditEntityType.PLAN_VERSION,
                version.getId(), before, versionSnapshot(version, OffsetDateTime.now(ZoneOffset.UTC)), null);
        return versionDto(version, OffsetDateTime.now(ZoneOffset.UTC));
    }

    @Transactional
    public PlanVersionDTO publish(Long versionId, String rawReason, BackofficeUser actor) {
        Long planId = findVersionPlanId(versionId);
        lockPlan(planId);
        PlanVersion version = findVersion(versionId);
        if (version.getStatus() != PlanVersionStatus.DRAFT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "La versión ya no está en borrador");
        }
        if (version.getEffectiveFrom() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Debes indicar desde cuándo entra en vigencia antes de publicar la versión");
        }
        String reason = audit.requireReason(rawReason);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (version.getEffectiveFrom().isBefore(now.minusMinutes(5))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "No se puede publicar una versión con vigencia retroactiva. Programa una fecha actual o futura");
        }
        // Un datetime-local enviado como "ahora" puede llegar unos segundos atrasado; se normaliza
        // al instante de publicación para no cerrar retroactivamente una versión anterior.
        if (version.getEffectiveFrom().isBefore(now)) {
            version.setEffectiveFrom(now);
        }
        validateDates(version.getEffectiveFrom(), version.getEffectiveUntil());

        List<PlanVersion> published = versions.findByPlanIdAndStatusOrderByEffectiveFromAsc(planId, PlanVersionStatus.PUBLISHED);
        for (PlanVersion other : published) {
            if (other.getEffectiveFrom() != null && !other.getEffectiveFrom().isBefore(version.getEffectiveFrom())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Ya existe una versión publicada con vigencia igual o posterior. Retírala o programa esta versión después");
            }
        }

        PlanVersion predecessor = published.isEmpty() ? null : published.get(published.size() - 1);
        Map<String, Object> predecessorBefore = null;
        if (predecessor != null
                && (predecessor.getEffectiveUntil() == null || predecessor.getEffectiveUntil().isAfter(version.getEffectiveFrom()))) {
            predecessorBefore = versionSnapshot(predecessor, now);
            predecessor.setEffectiveUntil(version.getEffectiveFrom());
            versions.save(predecessor);
        }

        Map<String, Object> before = versionSnapshot(version, now);
        version.setStatus(PlanVersionStatus.PUBLISHED);
        version.setPublishedAt(now);
        version.setRetiredAt(null);
        version = versions.save(version);

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("publishedVersion", versionSnapshot(version, now));
        if (predecessor != null && predecessorBefore != null) {
            after.put("previousVersionBefore", predecessorBefore);
            after.put("previousVersionAfter", versionSnapshot(predecessor, now));
        }
        audit.record(actor, AdminAuditAction.ADMIN_PUBLISH_PLAN_VERSION, AdminAuditEntityType.PLAN_VERSION,
                version.getId(), before, after, reason);
        return versionDto(version, now);
    }

    @Transactional
    public PlanVersionDTO retire(Long versionId, String rawReason, BackofficeUser actor) {
        Long planId = findVersionPlanId(versionId);
        lockPlan(planId);
        PlanVersion version = findVersion(versionId);
        if (version.getStatus() != PlanVersionStatus.PUBLISHED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Solo una versión publicada puede retirarse");
        }
        String reason = audit.requireReason(rawReason);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Map<String, Object> before = versionSnapshot(version, now);
        version.setStatus(PlanVersionStatus.RETIRED);
        version.setRetiredAt(now);
        // Una versión futura retirada nunca llegó a entrar en vigencia: conservar sus fechas
        // evita fabricar un rango inválido (effective_until anterior a effective_from).
        if (!now.isBefore(version.getEffectiveFrom())
                && (version.getEffectiveUntil() == null || version.getEffectiveUntil().isAfter(now))) {
            version.setEffectiveUntil(now);
        }
        version = versions.save(version);
        audit.record(actor, AdminAuditAction.ADMIN_RETIRE_PLAN_VERSION, AdminAuditEntityType.PLAN_VERSION,
                version.getId(), before, versionSnapshot(version, now), reason);
        return versionDto(version, now);
    }

    private PlanDTO dto(Plan plan, List<PlanVersion> items, OffsetDateTime now) {
        List<PlanVersionDTO> versionDtos = items.stream().map(item -> versionDto(item, now)).toList();
        PlanVersionDTO current = versionDtos.stream()
                .filter(item -> item.effectiveState() == EffectiveState.ACTIVE)
                .findFirst().orElse(null);
        PlanVersionDTO scheduled = versionDtos.stream()
                .filter(item -> item.effectiveState() == EffectiveState.SCHEDULED)
                .min(java.util.Comparator.comparing(PlanVersionDTO::effectiveFrom))
                .orElse(null);
        return new PlanDTO(plan.getId(), plan.getCode(), plan.getName(), plan.getDescription(), plan.isActive(),
                plan.getCreatedAt(), plan.getUpdatedAt(), current, scheduled, versionDtos);
    }

    private PlanVersionDTO versionDto(PlanVersion version, OffsetDateTime now) {
        return new PlanVersionDTO(
                version.getId(), version.getPlan().getId(), version.getVersion(), version.getMonthlyPrice(),
                periodPriceDtos(version), version.getCurrency(), version.getMaxPensions(), version.getMaxCollaborators(), version.getMaxPhotos(),
                version.getMaxVideos(), version.getFeaturedDays(), version.isAdvancedAnalytics(), version.isInquiryHistory(),
                version.isConsolidatedAnalytics(), version.isExportEnabled(), version.getEffectiveFrom(),
                version.getEffectiveUntil(), version.getStatus(), effectiveState(version, now), version.getPublishedAt(),
                version.getRetiredAt(), version.getCreatedAt(), version.getUpdatedAt()
        );
    }

    private EffectiveState effectiveState(PlanVersion version, OffsetDateTime now) {
        if (version.getStatus() == PlanVersionStatus.DRAFT) return EffectiveState.DRAFT;
        if (version.getStatus() == PlanVersionStatus.RETIRED) return EffectiveState.RETIRED;
        if (version.getEffectiveFrom() == null) return EffectiveState.DRAFT;
        if (now.isBefore(version.getEffectiveFrom())) return EffectiveState.SCHEDULED;
        if (version.getEffectiveUntil() != null && !now.isBefore(version.getEffectiveUntil())) return EffectiveState.EXPIRED;
        return EffectiveState.ACTIVE;
    }

    private Map<String, Object> planSnapshot(Plan plan) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", plan.getId());
        result.put("code", plan.getCode());
        result.put("name", plan.getName());
        result.put("description", plan.getDescription());
        result.put("active", plan.isActive());
        return result;
    }

    private Map<String, Object> versionSnapshot(PlanVersion version, OffsetDateTime now) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", version.getId());
        result.put("planId", version.getPlan().getId());
        result.put("version", version.getVersion());
        result.put("monthlyPrice", version.getMonthlyPrice());
        result.put("periodPrices", periodPriceDtos(version));
        result.put("currency", version.getCurrency());
        result.put("maxPensions", version.getMaxPensions());
        result.put("maxCollaborators", version.getMaxCollaborators());
        result.put("maxPhotos", version.getMaxPhotos());
        result.put("maxVideos", version.getMaxVideos());
        result.put("featuredDays", version.getFeaturedDays());
        result.put("advancedAnalytics", version.isAdvancedAnalytics());
        result.put("inquiryHistory", version.isInquiryHistory());
        result.put("consolidatedAnalytics", version.isConsolidatedAnalytics());
        result.put("exportEnabled", version.isExportEnabled());
        result.put("effectiveFrom", version.getEffectiveFrom());
        result.put("effectiveUntil", version.getEffectiveUntil());
        result.put("status", version.getStatus());
        result.put("effectiveState", effectiveState(version, now));
        return result;
    }

    private ValidatedVersion validateVersion(VersionInput input) {
        if (input == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Los datos de la versión son obligatorios");
        BigDecimal price = validatePrice(input.monthlyPrice(), "El precio mensual");
        List<PeriodPriceInput> periodPrices = validatePeriodPrices(input.periodPrices(), price);
        String currency = validateCurrency(input.currency());
        Integer maxPensions = validateLimit(input.maxPensions(), "máximo de pensiones");
        Integer maxCollaborators = validateLimit(input.maxCollaborators(), "máximo de colaboradores");
        Integer maxPhotos = validateLimit(input.maxPhotos(), "máximo de fotos");
        Integer maxVideos = validateLimit(input.maxVideos(), "máximo de videos");
        int featuredDays = input.featuredDays();
        if (featuredDays < 0 || featuredDays > MAX_FEATURED_DAYS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Los días destacados deben estar entre 0 y " + MAX_FEATURED_DAYS);
        }
        validateDates(input.effectiveFrom(), input.effectiveUntil());
        return new ValidatedVersion(price, periodPrices, currency, maxPensions, maxCollaborators, maxPhotos, maxVideos,
                featuredDays, input.advancedAnalytics(), input.inquiryHistory(), input.consolidatedAnalytics(),
                input.exportEnabled(), input.effectiveFrom(), input.effectiveUntil());
    }

    private void apply(PlanVersion version, ValidatedVersion data) {
        version.setMonthlyPrice(data.monthlyPrice());
        version.replacePeriodPrices(data.periodPrices().stream()
                .map(item -> PlanVersionPeriodPrice.builder()
                        .periodMonths(item.periodMonths())
                        .totalPrice(item.totalPrice())
                        .enabled(item.enabled())
                        .build())
                .toList());
        version.setCurrency(data.currency());
        version.setMaxPensions(data.maxPensions());
        version.setMaxCollaborators(data.maxCollaborators());
        version.setMaxPhotos(data.maxPhotos());
        version.setMaxVideos(data.maxVideos());
        version.setFeaturedDays(data.featuredDays());
        version.setAdvancedAnalytics(data.advancedAnalytics());
        version.setInquiryHistory(data.inquiryHistory());
        version.setConsolidatedAnalytics(data.consolidatedAnalytics());
        version.setExportEnabled(data.exportEnabled());
        version.setEffectiveFrom(data.effectiveFrom());
        version.setEffectiveUntil(data.effectiveUntil());
    }

    private List<PeriodPriceDTO> periodPriceDtos(PlanVersion version) {
        if (version.getPeriodPrices() == null || version.getPeriodPrices().isEmpty()) return List.of();
        return version.getPeriodPrices().stream()
                .sorted(java.util.Comparator.comparingInt(PlanVersionPeriodPrice::getPeriodMonths))
                .map(item -> new PeriodPriceDTO(
                        item.getPeriodMonths(),
                        item.getTotalPrice() == null ? BigDecimal.ZERO.setScale(2) : item.getTotalPrice().setScale(2, RoundingMode.HALF_UP),
                        item.isEnabled()))
                .toList();
    }

    private List<PeriodPriceInput> validatePeriodPrices(List<PeriodPriceInput> raw, BigDecimal monthlyPrice) {
        if (raw == null || raw.isEmpty()) {
            // Backward compatibility for older Backoffice clients: preserve the historical
            // monthlyPrice * months behavior, but persist it explicitly from now on.
            return SUPPORTED_SUBSCRIPTION_PERIODS.stream()
                    .map(months -> new PeriodPriceInput(
                            months,
                            monthlyPrice.multiply(BigDecimal.valueOf(months)).setScale(2, RoundingMode.HALF_UP),
                            true))
                    .toList();
        }

        Map<Integer, PeriodPriceInput> unique = new LinkedHashMap<>();
        for (PeriodPriceInput item : raw) {
            if (item == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La configuración de períodos contiene una entrada inválida");
            }
            int months = item.periodMonths();
            if (!SUPPORTED_SUBSCRIPTION_PERIODS.contains(months)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Solo se admiten períodos de 1, 3, 6 o 12 meses");
            }
            if (unique.containsKey(months)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "El período de " + months + " meses está repetido");
            }
            BigDecimal totalPrice = validatePrice(item.totalPrice(),
                    "El precio total para " + (months == 1 ? "1 mes" : months + " meses"));
            unique.put(months, new PeriodPriceInput(months, totalPrice, item.enabled()));
        }

        List<PeriodPriceInput> normalized = new ArrayList<>();
        for (Integer months : SUPPORTED_SUBSCRIPTION_PERIODS) {
            PeriodPriceInput item = unique.get(months);
            if (item != null) normalized.add(item);
        }
        return List.copyOf(normalized);
    }

    private BigDecimal validatePrice(BigDecimal raw, String label) {
        BigDecimal value = raw == null ? null : raw.stripTrailingZeros();
        if (value == null || value.signum() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + " no puede ser negativo");
        }
        if (value.scale() > 2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + " admite como máximo 2 decimales");
        }
        if (value.compareTo(new BigDecimal("9999999999.99")) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + " es demasiado alto");
        }
        return value.setScale(2, RoundingMode.UNNECESSARY);
    }

    private void validateDates(OffsetDateTime from, OffsetDateTime until) {
        if (from != null && until != null && !until.isAfter(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La fecha final de vigencia debe ser posterior a la fecha inicial");
        }
    }

    private Integer validateLimit(Integer value, String label) {
        if (value == null) return null; // NULL = sin límite comercial para ese beneficio.
        if (value < 0 || value > MAX_LIMIT_VALUE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El " + label + " debe estar entre 0 y " + MAX_LIMIT_VALUE + " o quedar vacío para sin límite");
        }
        return value;
    }

    private String validateCurrency(String raw) {
        String value = cleanRequired(raw, "La moneda", 3).toUpperCase(Locale.ROOT);
        if (value.length() != 3) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La moneda debe usar un código ISO 4217 de 3 letras");
        }
        try {
            Currency.getInstance(value);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El código de moneda no es válido");
        }
        return value;
    }

    private String cleanCode(String raw) {
        String code = cleanRequired(raw, "El código del plan", 30).toUpperCase(Locale.ROOT);
        if (!code.matches("[A-Z][A-Z0-9_]{1,29}")) {
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
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + " no puede superar " + max + " caracteres");
        }
        return value;
    }

    private Plan findPlan(Long id) {
        if (id == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El plan es obligatorio");
        return plans.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Plan no encontrado"));
    }

    private Plan lockPlan(Long id) {
        if (id == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El plan es obligatorio");
        return plans.findByIdForUpdate(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Plan no encontrado"));
    }

    private Long findVersionPlanId(Long id) {
        if (id == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La versión es obligatoria");
        return versions.findPlanIdByVersionId(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Versión de plan no encontrada"));
    }

    private PlanVersion findVersion(Long id) {
        if (id == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La versión es obligatoria");
        return versions.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Versión de plan no encontrada"));
    }

    public record CommercialConfigDTO(boolean monetizationEnabled, boolean paymentsEnabled) {}

    public enum EffectiveState { DRAFT, SCHEDULED, ACTIVE, EXPIRED, RETIRED }

    public record PlanDTO(
            Long id,
            String code,
            String name,
            String description,
            boolean active,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            PlanVersionDTO currentVersion,
            PlanVersionDTO nextScheduledVersion,
            List<PlanVersionDTO> versions
    ) {}

    public record PlanVersionDTO(
            Long id,
            Long planId,
            int version,
            BigDecimal monthlyPrice,
            List<PeriodPriceDTO> periodPrices,
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
            OffsetDateTime effectiveFrom,
            OffsetDateTime effectiveUntil,
            PlanVersionStatus status,
            EffectiveState effectiveState,
            OffsetDateTime publishedAt,
            OffsetDateTime retiredAt,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {}

    public record PeriodPriceDTO(
            int periodMonths,
            BigDecimal totalPrice,
            boolean enabled
    ) {}

    public record PeriodPriceInput(
            int periodMonths,
            BigDecimal totalPrice,
            boolean enabled
    ) {}

    public record VersionInput(
            BigDecimal monthlyPrice,
            List<PeriodPriceInput> periodPrices,
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
            OffsetDateTime effectiveFrom,
            OffsetDateTime effectiveUntil
    ) {}

    private record ValidatedVersion(
            BigDecimal monthlyPrice,
            List<PeriodPriceInput> periodPrices,
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
            OffsetDateTime effectiveFrom,
            OffsetDateTime effectiveUntil
    ) {}
}
