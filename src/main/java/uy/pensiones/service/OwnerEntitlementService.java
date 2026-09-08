package uy.pensiones.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import uy.pensiones.config.AppProperties;
import uy.pensiones.enums.EntitlementSource;
import uy.pensiones.enums.UserRole;
import uy.pensiones.model.LaunchCampaignBeneficiary;
import uy.pensiones.model.OwnerSubscription;
import uy.pensiones.model.OwnerTrialLifecycle;
import uy.pensiones.model.OwnerTrialSettings;
import uy.pensiones.model.Plan;
import uy.pensiones.model.PlanVersion;
import uy.pensiones.model.User;
import uy.pensiones.repo.*;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Single source of truth for owner commercial entitlements.
 *
 * Important rollout rule: while APP_MONETIZATION_ENABLED=false every guard is a no-op,
 * preserving the marketplace behavior that existed before monetization was introduced.
 */
@Service
public class OwnerEntitlementService {

    private final AppProperties properties;
    private final UserRepository users;
    private final OwnerSubscriptionRepository subscriptions;
    private final LaunchCampaignBeneficiaryRepository launchBenefits;
    private final PlanVersionRepository planVersions;
    private final OwnerEntitlementQueryRepository usage;
    private final OwnerEntitlementUserLockRepository userLocks;
    private final PensionRepository pensions;
    private final OwnerTrialLifecycleService trialLifecycle;

    public OwnerEntitlementService(AppProperties properties,
                                   UserRepository users,
                                   OwnerSubscriptionRepository subscriptions,
                                   LaunchCampaignBeneficiaryRepository launchBenefits,
                                   PlanVersionRepository planVersions,
                                   OwnerEntitlementQueryRepository usage,
                                   OwnerEntitlementUserLockRepository userLocks,
                                   PensionRepository pensions,
                                   OwnerTrialLifecycleService trialLifecycle) {
        this.properties = properties;
        this.users = users;
        this.subscriptions = subscriptions;
        this.launchBenefits = launchBenefits;
        this.planVersions = planVersions;
        this.usage = usage;
        this.userLocks = userLocks;
        this.pensions = pensions;
        this.trialLifecycle = trialLifecycle;
    }

    @Transactional(readOnly = true)
    public EntitlementSnapshot resolve(Long userId) {
        Long id = requireId(userId);
        User user = requireMarketplaceUser(users.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuario no encontrado")));
        return resolveInternal(user, OffsetDateTime.now(ZoneOffset.UTC), true);
    }

    /** Portfolio-wide limit. Serializes against grants/cancellations and concurrent pension creation. */
    @Transactional
    public void requireCanCreatePension(Long userId) {
        if (!properties.getMonetization().isEnabled()) return;
        User user = lockMarketplaceUser(userId);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        EffectivePolicy policy = policyForEnforcement(user, now);
        long current = usage.countResponsiblePensions(user.getId());
        requireCapacity("MAX_PENSIONS", policy.maxPensions(), current, 1,
                "Alcanzaste el máximo de pensiones permitido por tu plan.");
    }

    /** Publication is allowed during pending/active trial, grace, Founder benefit or paid subscription. */
    @Transactional(readOnly = true)
    public void requirePublicationAccess(Long userId) {
        if (!properties.getMonetization().isEnabled()) return;
        Long id = requireId(userId);
        User user = requireMarketplaceUser(users.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuario no encontrado")));
        Resolution resolution = resolvePolicy(user, OffsetDateTime.now(ZoneOffset.UTC));
        if (resolution.issueCode() != null) {
            throw new MonetizationConfigurationException(resolution.issueCode(), resolution.issueMessage());
        }
        if (!resolution.accessActive()) {
            throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED,
                    "Tu período gratuito ya finalizó. Contrata un plan para publicar o reactivar tus pensiones.");
        }
    }

    @Transactional(readOnly = true)
    public void requireAdvancedAnalytics(Long userId) {
        requireFeature(userId, "Tu plan no incluye analítica avanzada.", plan -> plan.advancedAnalytics());
    }

    @Transactional(readOnly = true)
    public void requireConsolidatedAnalytics(Long userId) {
        requireFeature(userId, "Tu plan no incluye analítica consolidada.", plan -> plan.consolidatedAnalytics());
    }

    @Transactional(readOnly = true)
    public void requireExportEnabled(Long userId) {
        requireFeature(userId, "Tu plan no incluye exportación de métricas.", plan -> plan.exportEnabled());
    }

    @Transactional(readOnly = true)
    public void requireInquiryHistory(Long userId) {
        requireFeature(userId, "Tu plan no incluye historial avanzado de consultas.", plan -> plan.inquiryHistory());
    }

    private void requireFeature(Long userId, String message, java.util.function.Predicate<EffectivePlan> enabled) {
        if (!properties.getMonetization().isEnabled()) return;
        EntitlementSnapshot snapshot = resolve(userId);
        if (!snapshot.configurationReady()) {
            throw new MonetizationConfigurationException(snapshot.issueCode(), snapshot.issueMessage());
        }
        if (!snapshot.accessActive()) {
            throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED,
                    "Tu acceso como propietario finalizó. Contrata un plan para continuar.");
        }
        if (snapshot.plan() == null || !enabled.test(snapshot.plan())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, message);
        }
    }

    /** Used when ownership is transferred to another marketplace account. */
    @Transactional
    public void requireCanTakeOwnership(Long userId, Long pensionId) {
        if (!properties.getMonetization().isEnabled()) return;
        Long cleanPensionId = requireId(pensionId);
        pensions.findByIdForEntitlementUpdate(cleanPensionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pensión no encontrada"));
        User user = lockMarketplaceUser(userId);
        Long currentResponsible = usage.findResponsibleUserId(cleanPensionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pensión no encontrada"));
        if (user.getId().equals(currentResponsible)) return;

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        EffectivePolicy policy = policyForEnforcement(user, now);
        long current = usage.countResponsiblePensions(user.getId());
        requireCapacity("MAX_PENSIONS", policy.maxPensions(), current, 1,
                "La cuenta destino alcanzó el máximo de pensiones permitido por su plan.");
    }

    /** max_collaborators is interpreted per pension and includes active members plus live pending invitations. */
    @Transactional
    public void requireCanAddCollaborator(Long pensionId) {
        if (!properties.getMonetization().isEnabled()) return;
        Long id = requireId(pensionId);
        pensions.findByIdForEntitlementUpdate(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pensión no encontrada"));
        Long ownerId = usage.findResponsibleUserId(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Responsable de la pensión no encontrado"));
        User owner = lockMarketplaceUser(ownerId);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        EffectivePolicy policy = policyForEnforcement(owner, now);
        long current = usage.countCollaboratorSlots(id, now);
        requireCapacity("MAX_COLLABORATORS_PER_PENSION", policy.maxCollaborators(), current, 1,
                "Esta pensión alcanzó el máximo de colaboradores permitido por el plan.");
    }

    /** max_photos and max_videos are interpreted per pension. YouTube links consume a video slot. */
    @Transactional
    public void requireCanAddMedia(Long pensionId, int photosToAdd, int videosToAdd) {
        if (!properties.getMonetization().isEnabled()) return;
        if (photosToAdd < 0 || videosToAdd < 0) {
            throw new IllegalArgumentException("La cantidad de archivos a agregar no puede ser negativa");
        }
        if (photosToAdd == 0 && videosToAdd == 0) return;

        Long id = requireId(pensionId);
        pensions.findByIdForEntitlementUpdate(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pensión no encontrada"));
        Long ownerId = usage.findResponsibleUserId(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Responsable de la pensión no encontrado"));
        User owner = lockMarketplaceUser(ownerId);
        EffectivePolicy policy = policyForEnforcement(owner, OffsetDateTime.now(ZoneOffset.UTC));

        if (photosToAdd > 0) {
            long currentPhotos = usage.countPhotos(id);
            requireCapacity("MAX_PHOTOS_PER_PENSION", policy.maxPhotos(), currentPhotos, photosToAdd,
                    "Esta pensión alcanzaría el máximo de fotos permitido por el plan.");
        }
        if (videosToAdd > 0) {
            long currentVideos = usage.countVideos(id);
            requireCapacity("MAX_VIDEOS_PER_PENSION", policy.maxVideos(), currentVideos, videosToAdd,
                    "Esta pensión alcanzaría el máximo de videos permitido por el plan.");
        }
    }

    private EntitlementSnapshot resolveInternal(User user, OffsetDateTime now, boolean includePensionUsage) {
        long responsiblePensions = usage.countResponsiblePensions(user.getId());
        List<OwnerEntitlementQueryRepository.PensionUsageRow> rows = includePensionUsage
                ? usage.findPensionUsage(user.getId(), now)
                : List.of();

        if (!properties.getMonetization().isEnabled()) {
            UsageSummary usageSummary = usageSummary(responsiblePensions, null);
            return new EntitlementSnapshot(
                    user.getId(), false, false, true, true, EntitlementSource.BYPASS,
                    null, null, null, null, null, null, usageSummary,
                    pensionUsage(rows, null), responsiblePensions > rows.size()
            );
        }

        Resolution resolution = resolvePolicy(user, now);
        if (resolution.issueCode() != null) {
            UsageSummary usageSummary = usageSummary(responsiblePensions, null);
            return new EntitlementSnapshot(
                    user.getId(), true, true, false, false, EntitlementSource.MISCONFIGURED,
                    resolution.issueCode(), resolution.issueMessage(), null, null, null, null, usageSummary,
                    pensionUsage(rows, null), responsiblePensions > rows.size()
            );
        }

        EffectivePolicy policy = resolution.policy();
        UsageSummary usageSummary = usageSummary(responsiblePensions, policy);
        return new EntitlementSnapshot(
                user.getId(), true, true, true, resolution.accessActive(), resolution.source(),
                null, null, policy == null ? null : planDto(policy),
                resolution.subscription() == null ? null : subscriptionDto(resolution.subscription()),
                resolution.launchBenefit() == null ? null : launchBenefitDto(resolution.launchBenefit()),
                resolution.trialAccess(), usageSummary, pensionUsage(rows, policy), responsiblePensions > rows.size()
        );
    }

    private EffectivePolicy policyForEnforcement(User user, OffsetDateTime now) {
        Resolution resolution = resolvePolicy(user, now);
        if (resolution.issueCode() != null) {
            throw new MonetizationConfigurationException(resolution.issueCode(), resolution.issueMessage());
        }
        if (!resolution.accessActive() || resolution.policy() == null) {
            throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED,
                    "Tu período gratuito ya finalizó. Contrata un plan para continuar usando las funciones de propietario.");
        }
        return resolution.policy();
    }

    private Resolution resolvePolicy(User user, OffsetDateTime now) {
        List<OwnerSubscription> active = subscriptions.findEffectiveActiveDetailed(user.getId(), now, uy.pensiones.enums.SubscriptionStatus.ACTIVE);
        if (active.size() > 1) {
            return Resolution.issue("MULTIPLE_ACTIVE_SUBSCRIPTIONS",
                    "La cuenta tiene más de una suscripción efectiva. Corrige las suscripciones antes de aplicar límites.");
        }
        if (active.size() == 1) {
            OwnerSubscription subscription = active.get(0);
            PlanVersion version = subscription.getPlanVersion();
            if (version == null || version.getPlan() == null) {
                return Resolution.issue("SUBSCRIPTION_PLAN_VERSION_MISSING",
                        "La suscripción vigente no tiene una versión de plan válida.");
            }
            return Resolution.subscription(policy(version), subscription);
        }

        LaunchCampaignBeneficiary launchBenefit = launchBenefits.findEffectiveDetailed(
                user.getId(), FounderLaunchCampaignService.FOUNDER_CAMPAIGN_CODE,
                uy.pensiones.enums.LaunchCampaignBeneficiaryStatus.ACTIVE, now).orElse(null);
        if (launchBenefit != null) {
            PlanVersion version = launchBenefit.getPlanVersion();
            if (version == null || version.getPlan() == null) {
                return Resolution.issue("LAUNCH_CAMPAIGN_PLAN_VERSION_MISSING",
                        "El beneficio Fundador vigente no conserva una versión de plan válida.");
            }
            return Resolution.launchCampaign(policy(version), launchBenefit);
        }

        OwnerTrialSettings settings;
        try {
            settings = trialLifecycle.settings();
        } catch (MonetizationConfigurationException ex) {
            return Resolution.issue(ex.getConfigurationCode(), ex.getMessage());
        }

        // Safe rollout: until Backoffice explicitly enables trial enforcement, preserve the old FREE policy.
        if (!settings.isEnabled()) {
            List<PlanVersion> freeVersions = planVersions.findEffectiveFreeVersions("FREE", uy.pensiones.enums.PlanVersionStatus.PUBLISHED, now);
            if (freeVersions.isEmpty()) {
                return Resolution.issue("FREE_PLAN_NOT_CONFIGURED",
                        "Monetización está habilitada pero no existe una versión FREE publicada y vigente.");
            }
            if (freeVersions.size() > 1) {
                return Resolution.issue("FREE_PLAN_OVERLAP",
                        "Existe más de una versión FREE vigente. Corrige las vigencias antes de aplicar límites.");
            }
            return Resolution.free(policy(freeVersions.get(0)));
        }

        PlanVersion configuredTrialPlan = settings.getTrialPlanVersion();
        if (!usableTrialPlan(configuredTrialPlan, now)) {
            return Resolution.issue("OWNER_TRIAL_PLAN_NOT_EFFECTIVE",
                    "La prueba gratuita está habilitada pero su versión de plan no está publicada y vigente.");
        }

        OwnerTrialLifecycle lifecycle = trialLifecycle.lifecycle(user.getId());
        if (lifecycle == null) {
            EffectiveTrialAccess trial = new EffectiveTrialAccess(
                    "PENDING", true, false, false, false, null, null, null,
                    settings.getDurationDays(), settings.getGraceDays(), null, null);
            return Resolution.trial(EntitlementSource.TRIAL_PENDING, policy(configuredTrialPlan), trial);
        }

        if (lifecycle.getConsumptionReason() == uy.pensiones.enums.OwnerTrialConsumptionReason.TRIAL_STARTED) {
            EffectiveTrialAccess trial = trialAccess(lifecycle, now);
            if (lifecycle.getConvertedAt() != null) {
                return Resolution.expired(trial.withPhase("CONVERTED"));
            }
            PlanVersion snapshot = lifecycle.getTrialPlanVersion();
            if (snapshot == null || snapshot.getPlan() == null) {
                return Resolution.issue("OWNER_TRIAL_PLAN_SNAPSHOT_MISSING",
                        "La prueba gratuita consumida no conserva una versión de plan válida.");
            }
            if (lifecycle.getTrialExpiresAt() != null && lifecycle.getTrialExpiresAt().isAfter(now)) {
                return Resolution.trial(EntitlementSource.TRIAL, policy(snapshot), trial.withPhase("ACTIVE"));
            }
            if (lifecycle.getGraceExpiresAt() != null && lifecycle.getGraceExpiresAt().isAfter(now)) {
                return Resolution.trial(EntitlementSource.TRIAL_GRACE, policy(snapshot), trial.withPhase("GRACE"));
            }
            return Resolution.expired(trial.withPhase("EXPIRED"));
        }

        if (lifecycle.getConsumptionReason() == uy.pensiones.enums.OwnerTrialConsumptionReason.FOUNDER_GRANTED) {
            OwnerTrialLifecycleService.FounderGrace grace = trialLifecycle.founderGrace(user.getId(), now);
            if (grace.active() && grace.benefit() != null && grace.benefit().getPlanVersion() != null
                    && grace.benefit().getPlanVersion().getPlan() != null) {
                EffectiveTrialAccess trial = new EffectiveTrialAccess(
                        "FOUNDER_GRACE", false, true, false, true, lifecycle.getConsumptionReason().name(),
                        null, grace.benefit().getExpiresAt(), grace.graceExpiresAt(), null, grace.graceDays(),
                        lifecycle.getConsumedAt(), lifecycle.getConvertedAt());
                return Resolution.founderGrace(policy(grace.benefit().getPlanVersion()), grace.benefit(), trial);
            }
            EffectiveTrialAccess trial = new EffectiveTrialAccess(
                    "FOUNDER_EXPIRED", false, true, true, false, lifecycle.getConsumptionReason().name(),
                    null, null, null, null, lifecycle.getGraceDaysSnapshot(), lifecycle.getConsumedAt(), lifecycle.getConvertedAt());
            return Resolution.expired(trial);
        }

        EffectiveTrialAccess paid = new EffectiveTrialAccess(
                "PAID_BEFORE_TRIAL", false, true, true, false, lifecycle.getConsumptionReason().name(),
                null, null, null, null, lifecycle.getGraceDaysSnapshot(), lifecycle.getConsumedAt(), lifecycle.getConvertedAt());
        return Resolution.expired(paid);
    }

    private EffectiveTrialAccess trialAccess(OwnerTrialLifecycle lifecycle, OffsetDateTime now) {
        String phase = "EXPIRED";
        if (lifecycle.getConvertedAt() != null) phase = "CONVERTED";
        else if (lifecycle.getTrialExpiresAt() != null && lifecycle.getTrialExpiresAt().isAfter(now)) phase = "ACTIVE";
        else if (lifecycle.getGraceExpiresAt() != null && lifecycle.getGraceExpiresAt().isAfter(now)) phase = "GRACE";
        return new EffectiveTrialAccess(
                phase, false, true, "EXPIRED".equals(phase) || "CONVERTED".equals(phase), "GRACE".equals(phase),
                lifecycle.getConsumptionReason() == null ? null : lifecycle.getConsumptionReason().name(),
                lifecycle.getTrialStartedAt(), lifecycle.getTrialExpiresAt(), lifecycle.getGraceExpiresAt(),
                lifecycle.getDurationDaysSnapshot(), lifecycle.getGraceDaysSnapshot(),
                lifecycle.getConsumedAt(), lifecycle.getConvertedAt());
    }

    private boolean usableTrialPlan(PlanVersion version, OffsetDateTime now) {
        if (version == null || version.getPlan() == null || !version.getPlan().isActive()) return false;
        if (version.getStatus() != uy.pensiones.enums.PlanVersionStatus.PUBLISHED) return false;
        if (version.getEffectiveFrom() == null || now.isBefore(version.getEffectiveFrom())) return false;
        return version.getEffectiveUntil() == null || now.isBefore(version.getEffectiveUntil());
    }

    private EffectivePolicy policy(PlanVersion version) {
        Plan plan = version.getPlan();
        return new EffectivePolicy(
                plan.getId(), plan.getCode(), plan.getName(), version.getId(), version.getVersion(),
                version.getMaxPensions(), version.getMaxCollaborators(), version.getMaxPhotos(), version.getMaxVideos(),
                version.getFeaturedDays(), version.isAdvancedAnalytics(), version.isInquiryHistory(),
                version.isConsolidatedAnalytics(), version.isExportEnabled()
        );
    }

    private EffectivePlan planDto(EffectivePolicy policy) {
        return new EffectivePlan(
                policy.planId(), policy.planCode(), policy.planName(), policy.planVersionId(), policy.planVersion(),
                policy.maxPensions(), policy.maxCollaborators(), policy.maxPhotos(), policy.maxVideos(),
                policy.featuredDays(), policy.advancedAnalytics(), policy.inquiryHistory(),
                policy.consolidatedAnalytics(), policy.exportEnabled()
        );
    }

    private EffectiveSubscription subscriptionDto(OwnerSubscription subscription) {
        return new EffectiveSubscription(
                subscription.getId(), subscription.getSource(), subscription.getStartedAt(), subscription.getExpiresAt()
        );
    }

    private EffectiveLaunchCampaignBenefit launchBenefitDto(LaunchCampaignBeneficiary benefit) {
        return new EffectiveLaunchCampaignBenefit(
                benefit.getId(),
                benefit.getCampaign().getCode(),
                benefit.getCampaign().getName(),
                benefit.getGrantedOrder(),
                benefit.getGrantedAt(),
                benefit.getExpiresAt(),
                benefit.getCampaign().getMaxFeaturedPensions()
        );
    }

    private UsageSummary usageSummary(long responsiblePensions,
                                      EffectivePolicy policy) {
        return new UsageSummary(
                responsiblePensions,
                policy == null ? null : remaining(policy.maxPensions(), responsiblePensions),
                policy != null && exceeded(policy.maxPensions(), responsiblePensions)
        );
    }

    private List<PensionUsage> pensionUsage(List<OwnerEntitlementQueryRepository.PensionUsageRow> rows,
                                            EffectivePolicy policy) {
        List<PensionUsage> result = new ArrayList<>(rows.size());
        for (var row : rows) {
            long members = safe(row.getMemberCount());
            long pendingInvites = safe(row.getPendingInviteCount());
            long collaboratorSlots = members + pendingInvites;
            long photos = safe(row.getPhotoCount());
            long videos = safe(row.getVideoCount());
            result.add(new PensionUsage(
                    row.getPensionId(), row.getPensionName(), members, pendingInvites, collaboratorSlots,
                    photos, videos,
                    policy == null ? null : remaining(policy.maxCollaborators(), collaboratorSlots),
                    policy == null ? null : remaining(policy.maxPhotos(), photos),
                    policy == null ? null : remaining(policy.maxVideos(), videos),
                    policy != null && exceeded(policy.maxCollaborators(), collaboratorSlots),
                    policy != null && exceeded(policy.maxPhotos(), photos),
                    policy != null && exceeded(policy.maxVideos(), videos)
            ));
        }
        return List.copyOf(result);
    }

    private void requireCapacity(String code, Integer limit, long current, int increment, String message) {
        if (limit == null) return;
        long projected = current + increment;
        if (projected > limit) {
            throw new EntitlementLimitExceededException(code, limit, current, message);
        }
    }

    private boolean exceeded(Integer limit, long usageValue) {
        return limit != null && usageValue > limit;
    }

    private Integer remaining(Integer limit, long current) {
        if (limit == null) return null;
        long value = Math.max(0L, (long) limit - current);
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    private long safe(Long value) {
        return value == null ? 0L : Math.max(0L, value);
    }

    private User lockMarketplaceUser(Long userId) {
        User user = userLocks.lockUser(requireId(userId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuario no encontrado"));
        return requireMarketplaceUser(user);
    }

    private User requireMarketplaceUser(User user) {
        if (user.getRole() != UserRole.SEEKER && user.getRole() != UserRole.OWNER) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuario del marketplace no encontrado");
        }
        return user;
    }

    private Long requireId(Long id) {
        if (id == null || id <= 0) throw new IllegalArgumentException("El identificador no es válido");
        return id;
    }

    private record EffectivePolicy(
            Long planId, String planCode, String planName, Long planVersionId, int planVersion,
            Integer maxPensions, Integer maxCollaborators, Integer maxPhotos, Integer maxVideos,
            int featuredDays, boolean advancedAnalytics, boolean inquiryHistory,
            boolean consolidatedAnalytics, boolean exportEnabled
    ) {}

    private record Resolution(EntitlementSource source, EffectivePolicy policy,
                              OwnerSubscription subscription, LaunchCampaignBeneficiary launchBenefit,
                              EffectiveTrialAccess trialAccess, boolean accessActive,
                              String issueCode, String issueMessage) {
        static Resolution subscription(EffectivePolicy policy, OwnerSubscription subscription) {
            return new Resolution(EntitlementSource.SUBSCRIPTION, policy, subscription, null, null, true, null, null);
        }
        static Resolution launchCampaign(EffectivePolicy policy, LaunchCampaignBeneficiary launchBenefit) {
            return new Resolution(EntitlementSource.LAUNCH_CAMPAIGN, policy, null, launchBenefit, null, true, null, null);
        }
        static Resolution founderGrace(EffectivePolicy policy, LaunchCampaignBeneficiary launchBenefit, EffectiveTrialAccess trial) {
            return new Resolution(EntitlementSource.LAUNCH_CAMPAIGN_GRACE, policy, null, launchBenefit, trial, true, null, null);
        }
        static Resolution trial(EntitlementSource source, EffectivePolicy policy, EffectiveTrialAccess trial) {
            return new Resolution(source, policy, null, null, trial, true, null, null);
        }
        static Resolution free(EffectivePolicy policy) {
            return new Resolution(EntitlementSource.FREE, policy, null, null, null, true, null, null);
        }
        static Resolution expired(EffectiveTrialAccess trial) {
            return new Resolution(EntitlementSource.ACCESS_EXPIRED, null, null, null, trial, false, null, null);
        }
        static Resolution issue(String code, String message) {
            return new Resolution(EntitlementSource.MISCONFIGURED, null, null, null, null, false, code, message);
        }
    }

    public record EntitlementSnapshot(
            Long userId,
            boolean monetizationEnabled,
            boolean enforcementEnabled,
            boolean configurationReady,
            boolean accessActive,
            EntitlementSource source,
            String issueCode,
            String issueMessage,
            EffectivePlan plan,
            EffectiveSubscription subscription,
            EffectiveLaunchCampaignBenefit launchCampaignBenefit,
            EffectiveTrialAccess trialAccess,
            UsageSummary usage,
            List<PensionUsage> pensions,
            boolean pensionUsageTruncated
    ) {}

    public record EffectiveTrialAccess(
            String phase,
            boolean eligible,
            boolean consumed,
            boolean expired,
            boolean inGrace,
            String consumptionReason,
            OffsetDateTime startedAt,
            OffsetDateTime trialExpiresAt,
            OffsetDateTime graceExpiresAt,
            Integer durationDays,
            Integer graceDays,
            OffsetDateTime consumedAt,
            OffsetDateTime convertedAt
    ) {
        EffectiveTrialAccess withPhase(String value) {
            return new EffectiveTrialAccess(value, eligible, consumed,
                    "EXPIRED".equals(value) || "CONVERTED".equals(value),
                    "GRACE".equals(value) || "FOUNDER_GRACE".equals(value), consumptionReason,
                    startedAt, trialExpiresAt, graceExpiresAt, durationDays, graceDays, consumedAt, convertedAt);
        }
    }

    public record EffectivePlan(
            Long planId, String planCode, String planName, Long planVersionId, int planVersion,
            Integer maxPensions, Integer maxCollaboratorsPerPension, Integer maxPhotosPerPension,
            Integer maxVideosPerPension, int featuredDays,
            boolean advancedAnalytics, boolean inquiryHistory,
            boolean consolidatedAnalytics, boolean exportEnabled
    ) {}

    public record EffectiveSubscription(
            Long id, uy.pensiones.enums.SubscriptionSource source,
            OffsetDateTime startedAt, OffsetDateTime expiresAt
    ) {}

    public record EffectiveLaunchCampaignBenefit(
            Long beneficiaryId, String campaignCode, String campaignName, int grantedOrder,
            OffsetDateTime grantedAt, OffsetDateTime expiresAt, int maxFeaturedPensions
    ) {}

    public record UsageSummary(
            long responsiblePensions,
            Integer remainingPensions,
            boolean pensionLimitExceeded
    ) {}

    public record PensionUsage(
            Long pensionId, String pensionName,
            long memberCount, long pendingInviteCount, long collaboratorSlots,
            long photoCount, long videoCount,
            Integer remainingCollaboratorSlots, Integer remainingPhotoSlots, Integer remainingVideoSlots,
            boolean collaboratorLimitExceeded, boolean photoLimitExceeded, boolean videoLimitExceeded
    ) {}
}
