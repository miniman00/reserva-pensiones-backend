package uy.pensiones.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import uy.pensiones.enums.AdminAuditAction;
import uy.pensiones.enums.AdminAuditEntityType;
import uy.pensiones.enums.LaunchCampaignBeneficiaryStatus;
import uy.pensiones.enums.LaunchCampaignEventType;
import uy.pensiones.enums.LaunchCampaignStatus;
import uy.pensiones.enums.PensionStatus;
import uy.pensiones.enums.PlanVersionStatus;
import uy.pensiones.model.BackofficeUser;
import uy.pensiones.model.LaunchCampaign;
import uy.pensiones.model.LaunchCampaignBeneficiary;
import uy.pensiones.model.LaunchCampaignEvent;
import uy.pensiones.model.Pension;
import uy.pensiones.model.Plan;
import uy.pensiones.model.PlanVersion;
import uy.pensiones.model.User;
import uy.pensiones.repo.LaunchCampaignBeneficiaryRepository;
import uy.pensiones.repo.LaunchCampaignEventRepository;
import uy.pensiones.repo.LaunchCampaignRepository;
import uy.pensiones.repo.PensionRepository;
import uy.pensiones.repo.PlanVersionRepository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AdminFounderCampaignService {

    private static final int MAX_BENEFICIARIES = 10_000;
    private static final int MAX_DURATION_DAYS = 3_650;
    private static final int MAX_FEATURED_PENSIONS = 1_000;
    private static final int MAX_EXTENSION_DAYS = 3_650;
    private static final String REVOKED_FEATURED_REASON = "Beneficio Fundador revocado desde Backoffice";

    private final LaunchCampaignRepository campaigns;
    private final LaunchCampaignBeneficiaryRepository beneficiaries;
    private final LaunchCampaignEventRepository events;
    private final PensionRepository pensions;
    private final PlanVersionRepository planVersions;
    private final FounderLaunchCampaignService founderCampaign;
    private final FounderFeaturedBenefitService featuredBenefits;
    private final OwnerTrialLifecycleService trialLifecycle;
    private final AdminAuditService audit;
    private final ObjectMapper objectMapper;

    public AdminFounderCampaignService(LaunchCampaignRepository campaigns,
                                       LaunchCampaignBeneficiaryRepository beneficiaries,
                                       LaunchCampaignEventRepository events,
                                       PensionRepository pensions,
                                       PlanVersionRepository planVersions,
                                       FounderLaunchCampaignService founderCampaign,
                                       FounderFeaturedBenefitService featuredBenefits,
                                       OwnerTrialLifecycleService trialLifecycle,
                                       AdminAuditService audit,
                                       ObjectMapper objectMapper) {
        this.campaigns = campaigns;
        this.beneficiaries = beneficiaries;
        this.events = events;
        this.pensions = pensions;
        this.planVersions = planVersions;
        this.founderCampaign = founderCampaign;
        this.featuredBenefits = featuredBenefits;
        this.trialLifecycle = trialLifecycle;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public OverviewDTO overview() {
        LaunchCampaign campaign = requireCampaign();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        long active = beneficiaries.countByCampaignIdAndStatusAndExpiresAtAfter(
                campaign.getId(), LaunchCampaignBeneficiaryStatus.ACTIVE, now);
        long revoked = beneficiaries.countByCampaignIdAndStatus(
                campaign.getId(), LaunchCampaignBeneficiaryStatus.REVOKED);
        long expiring30 = beneficiaries.countByCampaignIdAndStatusAndExpiresAtBetween(
                campaign.getId(), LaunchCampaignBeneficiaryStatus.ACTIVE, now, now.plusDays(30));
        return new OverviewDTO(
                campaignDto(campaign, now),
                new CampaignStatsDTO(
                        campaign.getGrantedCount(),
                        Math.max(0, campaign.getMaxBeneficiaries() - campaign.getGrantedCount()),
                        active,
                        revoked,
                        expiring30
                ),
                benefitPlanOptions(now)
        );
    }

    @Transactional
    public OverviewDTO updateConfig(ConfigInput input, String rawReason, BackofficeUser actor) {
        String reason = audit.requireReason(rawReason);
        LaunchCampaign campaign = requireCampaignForUpdate();
        validateConfig(input, campaign);
        Map<String, Object> before = campaignSnapshot(campaign);

        PlanVersion version = input.benefitPlanVersionId() == null
                ? null
                : requireConfigurablePlanVersion(input.benefitPlanVersionId());
        if (campaign.getStatus() == LaunchCampaignStatus.ACTIVE && version == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Una campaña activa no puede quedar sin una versión de plan configurada");
        }

        campaign.setMaxBeneficiaries(input.maxBeneficiaries());
        campaign.setBenefitDurationDays(input.benefitDurationDays());
        campaign.setMaxFeaturedPensions(input.maxFeaturedPensions());
        campaign.setBenefitPlanVersion(version);
        campaign.setEnrollmentStartsAt(input.enrollmentStartsAt());
        campaign.setEnrollmentEndsAt(input.enrollmentEndsAt());
        campaign.setShowRemainingSlots(input.showRemainingSlots());
        campaigns.save(campaign);

        Map<String, Object> after = campaignSnapshot(campaign);
        events.save(LaunchCampaignEvent.builder()
                .campaign(campaign)
                .backofficeUser(actor)
                .eventType(LaunchCampaignEventType.CONFIG_UPDATED)
                .reason(reason)
                .beforeJson(toJson(before))
                .afterJson(toJson(after))
                .build());
        audit.record(actor, AdminAuditAction.ADMIN_UPDATE_LAUNCH_CAMPAIGN,
                AdminAuditEntityType.LAUNCH_CAMPAIGN, campaign.getId(), before, after, reason);
        return overviewLocked(campaign);
    }

    @Transactional
    public OverviewDTO setStatus(LaunchCampaignStatus target, String rawReason, BackofficeUser actor) {
        String reason = audit.requireReason(rawReason);
        LaunchCampaign campaign = requireCampaignForUpdate();
        if (campaign.getStatus() == target) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, statusAlreadyMessage(target));
        }
        if (campaign.getStatus() == LaunchCampaignStatus.ENDED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "La campaña ya fue finalizada y no puede reabrirse");
        }
        if (target == LaunchCampaignStatus.ACTIVE) validateActivation(campaign);
        if (target != LaunchCampaignStatus.ACTIVE && target != LaunchCampaignStatus.PAUSED
                && target != LaunchCampaignStatus.ENDED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Estado de campaña no válido");
        }

        Map<String, Object> before = campaignSnapshot(campaign);
        campaign.setStatus(target);
        campaigns.save(campaign);
        Map<String, Object> after = campaignSnapshot(campaign);

        events.save(LaunchCampaignEvent.builder()
                .campaign(campaign)
                .backofficeUser(actor)
                .eventType(LaunchCampaignEventType.STATUS_CHANGED)
                .reason(reason)
                .beforeJson(toJson(before))
                .afterJson(toJson(after))
                .build());
        audit.record(actor, statusAuditAction(target), AdminAuditEntityType.LAUNCH_CAMPAIGN,
                campaign.getId(), before, after, reason);
        return overviewLocked(campaign);
    }

    @Transactional(readOnly = true)
    public Page<BeneficiaryDTO> beneficiaries(String rawQ,
                                               LaunchCampaignBeneficiaryStatus status,
                                               int page,
                                               int size) {
        LaunchCampaign campaign = requireCampaign();
        String q = rawQ == null ? "" : rawQ.trim();
        if (q.length() > 190) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La búsqueda no puede superar 190 caracteres");
        }
        PageRequest pageable = PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 100)),
                Sort.by(Sort.Direction.ASC, "grantedOrder"));
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return beneficiaries.searchAdmin(campaign.getId(), q, status, pageable).map(b -> beneficiaryDto(b, now));
    }

    @Transactional(readOnly = true)
    public Page<EventDTO> events(int page, int size) {
        LaunchCampaign campaign = requireCampaign();
        PageRequest pageable = PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 100)));
        return events.findByCampaignIdOrderByCreatedAtDescIdDesc(campaign.getId(), pageable).map(this::eventDto);
    }

    @Transactional
    public BeneficiaryDTO grantManually(Long pensionId, String rawReason, BackofficeUser actor) {
        String reason = audit.requireReason(rawReason);
        if (pensionId == null || pensionId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Debes indicar una pensión válida");
        }
        Pension pension = pensions.findWithOwnerById(pensionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pensión no encontrada"));
        if (pension.getStatus() != PensionStatus.PUBLISHED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El otorgamiento manual requiere una pensión publicada");
        }

        FounderLaunchCampaignService.GrantResult result = founderCampaign.grantManually(pension, actor, reason);
        if (!result.granted()) throw manualGrantError(result.decision());

        LaunchCampaignBeneficiary beneficiary = beneficiaries.findAdminDetailById(result.beneficiaryId())
                .orElseThrow(() -> new IllegalStateException("El beneficio Fundador recién creado no pudo recuperarse"));
        trialLifecycle.consumeByFounderBenefit(
                beneficiary.getUser(), beneficiary.getSourcePension(), beneficiary.getGrantedAt());
        Map<String, Object> after = beneficiarySnapshot(beneficiary);
        audit.record(actor, AdminAuditAction.ADMIN_GRANT_LAUNCH_CAMPAIGN_BENEFIT,
                AdminAuditEntityType.LAUNCH_CAMPAIGN_BENEFICIARY, beneficiary.getId(), null, after, reason);
        return beneficiaryDto(beneficiary, OffsetDateTime.now(ZoneOffset.UTC));
    }

    @Transactional
    public BeneficiaryDTO revoke(Long beneficiaryId, String rawReason, BackofficeUser actor) {
        String reason = audit.requireReason(rawReason);
        LaunchCampaign campaign = requireCampaignForUpdate();
        LaunchCampaignBeneficiary beneficiary = requireBeneficiaryForUpdate(beneficiaryId, campaign.getId());
        if (beneficiary.getStatus() == LaunchCampaignBeneficiaryStatus.REVOKED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "El beneficio Fundador ya está revocado");
        }

        Map<String, Object> before = beneficiarySnapshot(beneficiary);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        int cancelledPromotions = featuredBenefits.cancelActiveForBeneficiary(
                beneficiary, now, REVOKED_FEATURED_REASON);
        beneficiary.setStatus(LaunchCampaignBeneficiaryStatus.REVOKED);
        beneficiary.setRevokedAt(now);
        beneficiary.setRevocationReason(reason);
        beneficiaries.save(beneficiary);
        Map<String, Object> after = beneficiarySnapshot(beneficiary);
        after.put("cancelledFeaturedPromotions", cancelledPromotions);

        events.save(LaunchCampaignEvent.builder()
                .campaign(campaign)
                .beneficiary(beneficiary)
                .user(beneficiary.getUser())
                .pension(beneficiary.getSourcePension())
                .backofficeUser(actor)
                .eventType(LaunchCampaignEventType.REVOKED)
                .reason(reason)
                .beforeJson(toJson(before))
                .afterJson(toJson(after))
                .build());
        audit.record(actor, AdminAuditAction.ADMIN_REVOKE_LAUNCH_CAMPAIGN_BENEFIT,
                AdminAuditEntityType.LAUNCH_CAMPAIGN_BENEFICIARY, beneficiary.getId(), before, after, reason);
        return beneficiaryDto(beneficiary, now);
    }

    @Transactional
    public BeneficiaryDTO extend(Long beneficiaryId, int days, String rawReason, BackofficeUser actor) {
        String reason = audit.requireReason(rawReason);
        if (days < 1 || days > MAX_EXTENSION_DAYS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La extensión debe ser de 1 a " + MAX_EXTENSION_DAYS + " días");
        }
        LaunchCampaign campaign = requireCampaignForUpdate();
        LaunchCampaignBeneficiary beneficiary = requireBeneficiaryForUpdate(beneficiaryId, campaign.getId());
        if (beneficiary.getStatus() == LaunchCampaignBeneficiaryStatus.REVOKED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "No se puede extender un beneficio revocado");
        }

        Map<String, Object> before = beneficiarySnapshot(beneficiary);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime previousExpiresAt = beneficiary.getExpiresAt();
        OffsetDateTime base = previousExpiresAt != null && previousExpiresAt.isAfter(now)
                ? previousExpiresAt : now;
        OffsetDateTime newExpiresAt = base.plusDays(days);
        beneficiary.setExpiresAt(newExpiresAt);
        beneficiaries.save(beneficiary);
        int extendedPromotions = featuredBenefits.extendForBeneficiary(
                beneficiary, previousExpiresAt, newExpiresAt, now);
        // Extending an expired Founder grant restores commercial access and only reactivates
        // pensions that were paused automatically by the commercial lifecycle.
        trialLifecycle.consumeByFounderBenefit(
                beneficiary.getUser(), beneficiary.getSourcePension(), beneficiary.getGrantedAt());
        Map<String, Object> after = beneficiarySnapshot(beneficiary);
        after.put("extendedDays", days);
        after.put("extendedFeaturedPromotions", extendedPromotions);

        events.save(LaunchCampaignEvent.builder()
                .campaign(campaign)
                .beneficiary(beneficiary)
                .user(beneficiary.getUser())
                .pension(beneficiary.getSourcePension())
                .backofficeUser(actor)
                .eventType(LaunchCampaignEventType.EXTENDED)
                .reason(reason)
                .beforeJson(toJson(before))
                .afterJson(toJson(after))
                .build());
        audit.record(actor, AdminAuditAction.ADMIN_EXTEND_LAUNCH_CAMPAIGN_BENEFIT,
                AdminAuditEntityType.LAUNCH_CAMPAIGN_BENEFICIARY, beneficiary.getId(), before, after, reason);
        return beneficiaryDto(beneficiary, now);
    }

    private OverviewDTO overviewLocked(LaunchCampaign campaign) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        long active = beneficiaries.countByCampaignIdAndStatusAndExpiresAtAfter(
                campaign.getId(), LaunchCampaignBeneficiaryStatus.ACTIVE, now);
        long revoked = beneficiaries.countByCampaignIdAndStatus(
                campaign.getId(), LaunchCampaignBeneficiaryStatus.REVOKED);
        long expiring30 = beneficiaries.countByCampaignIdAndStatusAndExpiresAtBetween(
                campaign.getId(), LaunchCampaignBeneficiaryStatus.ACTIVE, now, now.plusDays(30));
        return new OverviewDTO(campaignDto(campaign, now),
                new CampaignStatsDTO(campaign.getGrantedCount(),
                        Math.max(0, campaign.getMaxBeneficiaries() - campaign.getGrantedCount()),
                        active, revoked, expiring30), benefitPlanOptions(now));
    }

    private LaunchCampaign requireCampaign() {
        return campaigns.findByCode(FounderLaunchCampaignService.FOUNDER_CAMPAIGN_CODE)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "La Campaña Propietarios Fundadores no está configurada"));
    }

    private LaunchCampaign requireCampaignForUpdate() {
        return campaigns.findByCodeForUpdate(FounderLaunchCampaignService.FOUNDER_CAMPAIGN_CODE)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "La Campaña Propietarios Fundadores no está configurada"));
    }

    private LaunchCampaignBeneficiary requireBeneficiaryForUpdate(Long id, Long campaignId) {
        if (id == null || id <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Beneficiario no válido");
        LaunchCampaignBeneficiary beneficiary = beneficiaries.findByIdForUpdate(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Beneficiario Fundador no encontrado"));
        if (beneficiary.getCampaign() == null || !campaignId.equals(beneficiary.getCampaign().getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Beneficiario Fundador no encontrado");
        }
        return beneficiary;
    }

    private void validateConfig(ConfigInput input, LaunchCampaign campaign) {
        if (input == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Configuración obligatoria");
        if (input.maxBeneficiaries() < 1 || input.maxBeneficiaries() > MAX_BENEFICIARIES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Los cupos deben estar entre 1 y " + MAX_BENEFICIARIES);
        }
        if (input.maxBeneficiaries() < campaign.getGrantedCount()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "No puedes reducir los cupos por debajo de los " + campaign.getGrantedCount() + " ya otorgados");
        }
        if (input.benefitDurationDays() < 1 || input.benefitDurationDays() > MAX_DURATION_DAYS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La duración debe estar entre 1 y " + MAX_DURATION_DAYS + " días");
        }
        if (input.maxFeaturedPensions() < 0 || input.maxFeaturedPensions() > MAX_FEATURED_PENSIONS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El máximo de destacados debe estar entre 0 y " + MAX_FEATURED_PENSIONS);
        }
        if (input.enrollmentStartsAt() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Debes indicar el inicio de inscripción");
        }
        if (input.enrollmentEndsAt() != null && !input.enrollmentEndsAt().isAfter(input.enrollmentStartsAt())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El fin de inscripción debe ser posterior al inicio");
        }
    }

    private void validateActivation(LaunchCampaign campaign) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (campaign.getGrantedCount() >= campaign.getMaxBeneficiaries()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "La campaña ya no tiene cupos disponibles");
        }
        if (campaign.getEnrollmentEndsAt() != null && !now.isBefore(campaign.getEnrollmentEndsAt())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "La fecha de inscripción de la campaña ya finalizó");
        }
        PlanVersion version = campaign.getBenefitPlanVersion();
        if (version == null || version.getPlan() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Configura una versión de plan para el beneficio antes de activar la campaña");
        }
        OffsetDateTime checkAt = campaign.getEnrollmentStartsAt() != null && campaign.getEnrollmentStartsAt().isAfter(now)
                ? campaign.getEnrollmentStartsAt() : now;
        if (!isPlanAvailableAt(version, checkAt)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "La versión de plan elegida no estará disponible al comenzar/continuar la inscripción");
        }
    }

    private PlanVersion requireConfigurablePlanVersion(Long id) {
        if (id == null || id <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Versión de plan no válida");
        PlanVersion version = planVersions.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Versión de plan no encontrada"));
        if (version.getPlan() == null || !version.getPlan().isActive() || version.getStatus() != PlanVersionStatus.PUBLISHED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El beneficio debe usar una versión publicada de un plan activo");
        }
        return version;
    }

    private boolean isPlanAvailableAt(PlanVersion version, OffsetDateTime at) {
        Plan plan = version.getPlan();
        if (plan == null || !plan.isActive() || version.getStatus() != PlanVersionStatus.PUBLISHED) return false;
        if (version.getEffectiveFrom() == null || at.isBefore(version.getEffectiveFrom())) return false;
        return version.getEffectiveUntil() == null || at.isBefore(version.getEffectiveUntil());
    }

    private List<BenefitPlanOptionDTO> benefitPlanOptions(OffsetDateTime now) {
        return planVersions.findAll().stream()
                .filter(v -> v.getPlan() != null && v.getPlan().isActive() && v.getStatus() == PlanVersionStatus.PUBLISHED)
                .filter(v -> v.getEffectiveUntil() == null || v.getEffectiveUntil().isAfter(now))
                .sorted(Comparator.comparing((PlanVersion v) -> v.getPlan().getName(), String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(PlanVersion::getVersion).reversed())
                .map(v -> new BenefitPlanOptionDTO(v.getId(), v.getPlan().getId(), v.getPlan().getCode(),
                        v.getPlan().getName(), v.getVersion(), v.getMonthlyPrice(), v.getCurrency(),
                        v.getEffectiveFrom(), v.getEffectiveUntil(), isPlanAvailableAt(v, now)))
                .toList();
    }

    private CampaignDTO campaignDto(LaunchCampaign campaign, OffsetDateTime now) {
        PlanVersion version = campaign.getBenefitPlanVersion();
        return new CampaignDTO(campaign.getId(), campaign.getCode(), campaign.getName(), campaign.getStatus(),
                campaign.getMaxBeneficiaries(), campaign.getGrantedCount(), campaign.getBenefitDurationDays(),
                campaign.getMaxFeaturedPensions(), version == null ? null : version.getId(),
                version == null || version.getPlan() == null ? null : version.getPlan().getName(),
                version == null ? null : version.getVersion(), campaign.getEnrollmentStartsAt(),
                campaign.getEnrollmentEndsAt(), campaign.isShowRemainingSlots(),
                campaign.getGrantedCount() >= campaign.getMaxBeneficiaries(),
                campaign.getStatus() == LaunchCampaignStatus.ACTIVE
                        && (campaign.getEnrollmentStartsAt() == null || !now.isBefore(campaign.getEnrollmentStartsAt()))
                        && (campaign.getEnrollmentEndsAt() == null || now.isBefore(campaign.getEnrollmentEndsAt()))
                        && campaign.getGrantedCount() < campaign.getMaxBeneficiaries(),
                campaign.getUpdatedAt());
    }

    private BeneficiaryDTO beneficiaryDto(LaunchCampaignBeneficiary b, OffsetDateTime now) {
        User user = b.getUser();
        Pension pension = b.getSourcePension();
        PlanVersion version = b.getPlanVersion();
        String effectiveState = b.getStatus() == LaunchCampaignBeneficiaryStatus.REVOKED ? "REVOKED"
                : (b.getExpiresAt() != null && !b.getExpiresAt().isAfter(now) ? "EXPIRED" : "ACTIVE");
        return new BeneficiaryDTO(b.getId(), b.getGrantedOrder(), user == null ? null : user.getId(),
                user == null ? null : user.getName(), user == null ? null : user.getEmail(),
                user != null && user.isSuspended(), pension == null ? null : pension.getId(),
                pension == null ? null : pension.getName(), b.getGrantedAt(), b.getExpiresAt(),
                b.getStatus(), effectiveState, version == null ? null : version.getId(),
                version == null || version.getPlan() == null ? null : version.getPlan().getName(),
                version == null ? null : version.getVersion(), promisedFeatured(b),
                b.getGrantedByBackofficeUser() == null ? null : b.getGrantedByBackofficeUser().getDisplayName(),
                b.getGrantReason(), b.getRevokedAt(), b.getRevocationReason());
    }

    private int promisedFeatured(LaunchCampaignBeneficiary b) {
        if (b.getMaxFeaturedPensionsSnapshot() != null) return Math.max(0, b.getMaxFeaturedPensionsSnapshot());
        return b.getCampaign() == null ? 0 : Math.max(0, b.getCampaign().getMaxFeaturedPensions());
    }

    private EventDTO eventDto(LaunchCampaignEvent e) {
        return new EventDTO(e.getId(), e.getEventType(), e.getBeneficiary() == null ? null : e.getBeneficiary().getId(),
                e.getUser() == null ? null : e.getUser().getId(),
                e.getUser() == null ? null : e.getUser().getName(),
                e.getPension() == null ? null : e.getPension().getId(),
                e.getPension() == null ? null : e.getPension().getName(),
                e.getBackofficeUser() == null ? null : e.getBackofficeUser().getDisplayName(),
                e.getReason(), e.getBeforeJson(), e.getAfterJson(), e.getCreatedAt());
    }

    private Map<String, Object> campaignSnapshot(LaunchCampaign c) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("code", c.getCode());
        map.put("status", c.getStatus());
        map.put("maxBeneficiaries", c.getMaxBeneficiaries());
        map.put("grantedCount", c.getGrantedCount());
        map.put("benefitDurationDays", c.getBenefitDurationDays());
        map.put("maxFeaturedPensions", c.getMaxFeaturedPensions());
        map.put("benefitPlanVersionId", c.getBenefitPlanVersion() == null ? null : c.getBenefitPlanVersion().getId());
        map.put("enrollmentStartsAt", c.getEnrollmentStartsAt());
        map.put("enrollmentEndsAt", c.getEnrollmentEndsAt());
        map.put("showRemainingSlots", c.isShowRemainingSlots());
        return map;
    }

    private Map<String, Object> beneficiarySnapshot(LaunchCampaignBeneficiary b) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("campaignId", b.getCampaign() == null ? null : b.getCampaign().getId());
        map.put("userId", b.getUser() == null ? null : b.getUser().getId());
        map.put("sourcePensionId", b.getSourcePension() == null ? null : b.getSourcePension().getId());
        map.put("planVersionId", b.getPlanVersion() == null ? null : b.getPlanVersion().getId());
        map.put("maxFeaturedPensions", promisedFeatured(b));
        map.put("grantedOrder", b.getGrantedOrder());
        map.put("grantedAt", b.getGrantedAt());
        map.put("expiresAt", b.getExpiresAt());
        map.put("status", b.getStatus());
        map.put("revokedAt", b.getRevokedAt());
        return map;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("No se pudo serializar la auditoría de la campaña", ex);
        }
    }

    private ResponseStatusException manualGrantError(FounderLaunchCampaignService.GrantDecision decision) {
        return switch (decision) {
            case ALREADY_BENEFICIARY -> new ResponseStatusException(HttpStatus.CONFLICT,
                    "El propietario de esa pensión ya recibió el beneficio Fundador");
            case CAMPAIGN_FULL -> new ResponseStatusException(HttpStatus.CONFLICT,
                    "La campaña ya alcanzó el máximo de beneficiarios");
            case CAMPAIGN_ENDED -> new ResponseStatusException(HttpStatus.CONFLICT,
                    "La campaña fue finalizada y no admite nuevos otorgamientos");
            case BENEFIT_PLAN_NOT_CONFIGURED -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Configura el plan del beneficio antes de otorgarlo");
            case BENEFIT_PLAN_NOT_AVAILABLE -> new ResponseStatusException(HttpStatus.CONFLICT,
                    "La versión de plan configurada no está vigente para otorgar el beneficio");
            case INELIGIBLE_OWNER -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El responsable de la pensión no es elegible para el beneficio");
            case NOT_PUBLISHED -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La pensión debe estar publicada para otorgar el beneficio");
            default -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "No se pudo otorgar el beneficio Fundador: " + decision);
        };
    }

    private String statusAlreadyMessage(LaunchCampaignStatus status) {
        return switch (status) {
            case ACTIVE -> "La campaña ya está activa";
            case PAUSED -> "La campaña ya está pausada";
            case ENDED -> "La campaña ya está finalizada";
        };
    }

    private AdminAuditAction statusAuditAction(LaunchCampaignStatus status) {
        return switch (status) {
            case ACTIVE -> AdminAuditAction.ADMIN_ACTIVATE_LAUNCH_CAMPAIGN;
            case PAUSED -> AdminAuditAction.ADMIN_PAUSE_LAUNCH_CAMPAIGN;
            case ENDED -> AdminAuditAction.ADMIN_END_LAUNCH_CAMPAIGN;
        };
    }

    public record ConfigInput(int maxBeneficiaries,
                              int benefitDurationDays,
                              int maxFeaturedPensions,
                              Long benefitPlanVersionId,
                              OffsetDateTime enrollmentStartsAt,
                              OffsetDateTime enrollmentEndsAt,
                              boolean showRemainingSlots) {}

    public record OverviewDTO(CampaignDTO campaign, CampaignStatsDTO stats,
                              List<BenefitPlanOptionDTO> benefitPlanOptions) {}

    public record CampaignDTO(Long id, String code, String name, LaunchCampaignStatus status,
                              int maxBeneficiaries, int grantedCount, int benefitDurationDays,
                              int maxFeaturedPensions, Long benefitPlanVersionId, String benefitPlanName,
                              Integer benefitPlanVersion, OffsetDateTime enrollmentStartsAt,
                              OffsetDateTime enrollmentEndsAt, boolean showRemainingSlots,
                              boolean full, boolean acceptingAutomaticGrants, OffsetDateTime updatedAt) {}

    public record CampaignStatsDTO(int granted, int remaining, long active, long revoked, long expiringWithin30Days) {}

    public record BenefitPlanOptionDTO(Long planVersionId, Long planId, String planCode, String planName,
                                       int version, BigDecimal monthlyPrice, String currency,
                                       OffsetDateTime effectiveFrom, OffsetDateTime effectiveUntil,
                                       boolean currentlyAvailable) {}

    public record BeneficiaryDTO(Long id, int grantedOrder, Long userId, String ownerName, String ownerEmail,
                                 boolean ownerSuspended, Long sourcePensionId, String sourcePensionName,
                                 OffsetDateTime grantedAt, OffsetDateTime expiresAt,
                                 LaunchCampaignBeneficiaryStatus status, String effectiveState,
                                 Long planVersionId, String planName, Integer planVersion,
                                 int maxFeaturedPensions, String grantedBy, String grantReason,
                                 OffsetDateTime revokedAt, String revocationReason) {}

    public record EventDTO(Long id, LaunchCampaignEventType eventType, Long beneficiaryId, Long userId,
                           String ownerName, Long pensionId, String pensionName, String backofficeActor,
                           String reason, String beforeJson, String afterJson, OffsetDateTime createdAt) {}
}
