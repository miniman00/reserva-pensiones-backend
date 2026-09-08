package uy.pensiones.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import uy.pensiones.config.AppProperties;
import uy.pensiones.enums.*;
import uy.pensiones.mail.MailService;
import uy.pensiones.model.*;
import uy.pensiones.repo.*;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;

/**
 * Owns the one-time free-access lifecycle for marketplace owners.
 *
 * A user may consume the opportunity exactly once: by starting the normal trial, receiving the
 * Founder benefit, or paying before using a trial. Trial dates are snapshotted so later Backoffice
 * changes never rewrite conditions that were already granted.
 */
@Service
public class OwnerTrialLifecycleService {

    public static final short SETTINGS_ID = 1;
    public static final String COMMERCIAL_PAUSE_REASON = "FREE_ACCESS_EXPIRED";

    private static final Logger log = LoggerFactory.getLogger(OwnerTrialLifecycleService.class);
    private static final String COMMERCIAL_PATH = "/profile/commercial";

    private final OwnerTrialSettingsRepository settingsRepository;
    private final OwnerTrialLifecycleRepository lifecycles;
    private final OwnerEntitlementUserLockRepository userLocks;
    private final OwnerSubscriptionRepository subscriptions;
    private final LaunchCampaignBeneficiaryRepository founderBenefits;
    private final PensionRepository pensions;
    private final PensionPublicationService publication;
    private final NotificationService notifications;
    private final MailService mail;
    private final AppProperties appProperties;
    private final ZoneId businessZone;
    private final DateTimeFormatter dateFormatter;

    public OwnerTrialLifecycleService(OwnerTrialSettingsRepository settingsRepository,
                                      OwnerTrialLifecycleRepository lifecycles,
                                      OwnerEntitlementUserLockRepository userLocks,
                                      OwnerSubscriptionRepository subscriptions,
                                      LaunchCampaignBeneficiaryRepository founderBenefits,
                                      PensionRepository pensions,
                                      PensionPublicationService publication,
                                      NotificationService notifications,
                                      MailService mail,
                                      AppProperties appProperties,
                                      @Value("${app.owner-trial-notifications.zone:America/Montevideo}") String zone) {
        this.settingsRepository = settingsRepository;
        this.lifecycles = lifecycles;
        this.userLocks = userLocks;
        this.subscriptions = subscriptions;
        this.founderBenefits = founderBenefits;
        this.pensions = pensions;
        this.publication = publication;
        this.notifications = notifications;
        this.mail = mail;
        this.appProperties = appProperties;
        this.businessZone = safeZone(zone);
        this.dateFormatter = DateTimeFormatter.ofPattern("d 'de' MMMM 'de' uuuu", new Locale("es", "UY"));
    }

    @Transactional(readOnly = true)
    public OwnerTrialSettings settings() {
        return settingsRepository.findDetailedById(SETTINGS_ID)
                .orElseThrow(() -> new MonetizationConfigurationException(
                        "OWNER_TRIAL_SETTINGS_MISSING", "No existe la configuración de prueba gratuita para propietarios."));
    }

    @Transactional(readOnly = true)
    public OwnerTrialLifecycle lifecycle(Long userId) {
        if (userId == null || userId <= 0) return null;
        return lifecycles.findByUserId(userId).orElse(null);
    }

    /** Called after the pension was successfully persisted as PUBLISHED in the same transaction. */
    @Transactional
    public OwnerTrialLifecycle onFirstValidPublication(Pension pension,
                                                       FounderLaunchCampaignService.GrantResult founderResult) {
        if (pension == null || pension.getId() == null || pension.getStatus() != PensionStatus.PUBLISHED) return null;
        User responsible = pension.getOwner() != null ? pension.getOwner() : pension.getCreatedBy();
        if (responsible == null || responsible.getId() == null) return null;

        User lockedUser = userLocks.lockUser(responsible.getId()).orElse(null);
        if (lockedUser == null) return null;
        OwnerTrialLifecycle existing = lifecycles.findByUserIdForUpdate(lockedUser.getId()).orElse(null);
        if (existing != null) return existing;

        OwnerTrialSettings settings = settingsRepository.findDetailedById(SETTINGS_ID).orElse(null);
        int graceDays = settings == null ? 7 : Math.max(0, settings.getGraceDays());
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        if (isFounderDecision(founderResult) || hasFounderHistory(lockedUser.getId())) {
            return lifecycles.save(OwnerTrialLifecycle.builder()
                    .user(lockedUser)
                    .consumptionReason(OwnerTrialConsumptionReason.FOUNDER_GRANTED)
                    .sourcePension(pension)
                    .consumedAt(now)
                    .graceDaysSnapshot(graceDays)
                    .build());
        }

        if (subscriptions.existsByUserIdAndSource(lockedUser.getId(), SubscriptionSource.PAYMENT)) {
            return lifecycles.save(OwnerTrialLifecycle.builder()
                    .user(lockedUser)
                    .consumptionReason(OwnerTrialConsumptionReason.PAID_DIRECT)
                    .sourcePension(pension)
                    .consumedAt(now)
                    .graceDaysSnapshot(graceDays)
                    .build());
        }

        if (settings == null || !settings.isEnabled()) return null;
        PlanVersion trialPlan = requireUsableTrialPlan(settings, now);
        return startTrial(lockedUser, pension, trialPlan, settings, now);
    }

    /**
     * A Founder grant replaces any pending/active trial opportunity. This is also used by manual
     * Backoffice grants and extensions so an owner can never stack 90 trial days after Founder.
     */
    @Transactional
    public OwnerTrialLifecycle consumeByFounderBenefit(User user, Pension source, OffsetDateTime when) {
        if (user == null || user.getId() == null) return null;
        OffsetDateTime now = when == null ? OffsetDateTime.now(ZoneOffset.UTC) : when;
        User locked = userLocks.lockUser(user.getId()).orElse(user);
        OwnerTrialLifecycle lifecycle = lifecycles.findByUserIdForUpdate(locked.getId()).orElse(null);
        OwnerTrialSettings settings = settingsRepository.findDetailedById(SETTINGS_ID).orElse(null);
        int graceDays = settings == null ? 7 : Math.max(0, settings.getGraceDays());

        if (lifecycle == null) {
            lifecycle = OwnerTrialLifecycle.builder()
                    .user(locked)
                    .consumptionReason(OwnerTrialConsumptionReason.FOUNDER_GRANTED)
                    .sourcePension(source)
                    .consumedAt(now)
                    .graceDaysSnapshot(graceDays)
                    .build();
        } else {
            lifecycle.setConsumptionReason(OwnerTrialConsumptionReason.FOUNDER_GRANTED);
            if (source != null) lifecycle.setSourcePension(source);
            lifecycle.setConsumedAt(now);
            lifecycle.setTrialPlanVersion(null);
            lifecycle.setTrialStartedAt(null);
            lifecycle.setTrialExpiresAt(null);
            lifecycle.setGraceExpiresAt(null);
            lifecycle.setDurationDaysSnapshot(null);
            lifecycle.setGraceDaysSnapshot(graceDays);
            lifecycle.setConvertedAt(null);
            lifecycle.setAccessSuspendedAt(null);
        }
        lifecycle = lifecycles.save(lifecycle);
        reactivateCommerciallyPaused(locked, now);
        return lifecycle;
    }

    /** Paid conversion consumes a pending/active trial and must never fall back to free access later. */
    @Transactional
    public OwnerTrialLifecycle consumeByPaidSubscription(User user, OffsetDateTime when) {
        if (user == null || user.getId() == null) return null;
        OffsetDateTime now = when == null ? OffsetDateTime.now(ZoneOffset.UTC) : when;
        User locked = userLocks.lockUser(user.getId()).orElse(user);
        OwnerTrialLifecycle lifecycle = lifecycles.findByUserIdForUpdate(locked.getId()).orElse(null);
        OwnerTrialSettings settings = settingsRepository.findDetailedById(SETTINGS_ID).orElse(null);
        int graceDays = settings == null ? 7 : Math.max(0, settings.getGraceDays());

        if (lifecycle == null) {
            lifecycle = lifecycles.save(OwnerTrialLifecycle.builder()
                    .user(locked)
                    .consumptionReason(OwnerTrialConsumptionReason.PAID_DIRECT)
                    .consumedAt(now)
                    .graceDaysSnapshot(graceDays)
                    .build());
        } else if (lifecycle.getConsumptionReason() == OwnerTrialConsumptionReason.TRIAL_STARTED
                && lifecycle.getConvertedAt() == null) {
            lifecycle.setConvertedAt(now);
            lifecycle.setAccessSuspendedAt(null);
            lifecycle = lifecycles.save(lifecycle);
        } else {
            lifecycle.setAccessSuspendedAt(null);
            lifecycle = lifecycles.save(lifecycle);
        }
        reactivateCommerciallyPaused(locked, now);
        return lifecycle;
    }

    /** Safe rollout helper used when Backoffice enables the trial policy after the migration. */
    @Transactional
    public int backfillPublishedOwners(OwnerTrialSettings settings, OffsetDateTime now) {
        if (settings == null || !settings.isEnabled()) return 0;
        OffsetDateTime instant = now == null ? OffsetDateTime.now(ZoneOffset.UTC) : now;
        PlanVersion trialPlan = requireUsableTrialPlan(settings, instant);
        int created = 0;
        for (Long userId : pensions.findDistinctPublishedResponsibleUserIds()) {
            if (userId == null || lifecycles.findByUserId(userId).isPresent()) continue;
            User user = userLocks.lockUser(userId).orElse(null);
            if (user == null || lifecycles.findByUserIdForUpdate(userId).isPresent()) continue;
            Pension source = pensions.findPublishedForResponsibleUser(userId).stream().findFirst().orElse(null);
            if (hasFounderHistory(userId)) {
                lifecycles.save(OwnerTrialLifecycle.builder()
                        .user(user)
                        .consumptionReason(OwnerTrialConsumptionReason.FOUNDER_GRANTED)
                        .sourcePension(source)
                        .consumedAt(instant)
                        .graceDaysSnapshot(settings.getGraceDays())
                        .build());
            } else if (subscriptions.existsByUserIdAndSource(userId, SubscriptionSource.PAYMENT)) {
                lifecycles.save(OwnerTrialLifecycle.builder()
                        .user(user)
                        .consumptionReason(OwnerTrialConsumptionReason.PAID_DIRECT)
                        .sourcePension(source)
                        .consumedAt(instant)
                        .graceDaysSnapshot(settings.getGraceDays())
                        .build());
            } else {
                startTrial(user, source, trialPlan, settings, instant);
            }
            created++;
        }
        return created;
    }

    @Transactional(readOnly = true)
    public FounderGrace founderGrace(Long userId, OffsetDateTime now) {
        OwnerTrialLifecycle lifecycle = lifecycles.findByUserId(userId).orElse(null);
        if (lifecycle == null || lifecycle.getConsumptionReason() != OwnerTrialConsumptionReason.FOUNDER_GRANTED) {
            return FounderGrace.none();
        }
        LaunchCampaignBeneficiary benefit = latestFounder(userId);
        if (benefit == null || benefit.getStatus() != LaunchCampaignBeneficiaryStatus.ACTIVE || benefit.getExpiresAt() == null) {
            return FounderGrace.none();
        }
        OffsetDateTime graceUntil = benefit.getExpiresAt().plusDays(Math.max(0, lifecycle.getGraceDaysSnapshot()));
        OffsetDateTime instant = now == null ? OffsetDateTime.now(ZoneOffset.UTC) : now;
        return new FounderGrace(
                !benefit.getExpiresAt().isAfter(instant) && graceUntil.isAfter(instant),
                benefit,
                graceUntil,
                Math.max(0, lifecycle.getGraceDaysSnapshot())
        );
    }

    @Scheduled(
            cron = "${app.owner-trial-notifications.cron:0 20 * * * *}",
            zone = "${app.owner-trial-notifications.zone:America/Montevideo}"
    )
    @Transactional
    public void maintainOwnerAccess() {
        processLifecycle(OffsetDateTime.now(ZoneOffset.UTC));
    }

    void processLifecycle(OffsetDateTime now) {
        OffsetDateTime instant = now == null ? OffsetDateTime.now(ZoneOffset.UTC) : now;
        for (OwnerTrialLifecycle lifecycle : lifecycles.findByAccessSuspendedAtIsNullOrderByConsumedAtAsc()) {
            if (lifecycle == null || lifecycle.getUser() == null || lifecycle.getUser().getId() == null) continue;
            User owner = lifecycle.getUser();
            if (owner.isSuspended()) continue;
            if (hasActiveSubscription(owner.getId(), instant)) continue;
            // Founder is authoritative even for historical/manual grants that predate this lifecycle row.
            if (hasActiveFounderBenefit(owner.getId(), instant)) continue;

            if (lifecycle.getConsumptionReason() == OwnerTrialConsumptionReason.TRIAL_STARTED) {
                processTrial(lifecycle, owner, instant);
                continue;
            }
            if (lifecycle.getConsumptionReason() == OwnerTrialConsumptionReason.FOUNDER_GRANTED) {
                processFounderExpiry(lifecycle, owner, instant);
                continue;
            }
            if (lifecycle.getConsumptionReason() == OwnerTrialConsumptionReason.PAID_DIRECT) {
                suspendAccess(lifecycle, owner, instant, "Tu plan finalizó y no tienes un período gratuito disponible.");
            }
        }
    }

    private void processTrial(OwnerTrialLifecycle lifecycle, User owner, OffsetDateTime now) {
        if (lifecycle.getConvertedAt() != null) {
            suspendAccess(lifecycle, owner, now, "Tu suscripción finalizó y la prueba gratuita ya fue utilizada.");
            return;
        }
        if (lifecycle.getTrialExpiresAt() == null || lifecycle.getGraceExpiresAt() == null) return;

        if (!lifecycle.getTrialExpiresAt().isAfter(now)) {
            if (lifecycle.getGraceExpiresAt().isAfter(now)) {
                notifyTrialGrace(lifecycle, owner);
            } else {
                suspendAccess(lifecycle, owner, now,
                        "Finalizó tu prueba gratuita y también el período de gracia. Contrata un plan para volver a publicar tus pensiones.");
            }
            return;
        }

        long daysRemaining = ChronoUnit.DAYS.between(
                now.atZoneSameInstant(businessZone).toLocalDate(),
                lifecycle.getTrialExpiresAt().atZoneSameInstant(businessZone).toLocalDate());
        int milestone = trialMilestone(daysRemaining);
        if (milestone > 0) notifyTrialReminder(lifecycle, owner, milestone, daysRemaining);
    }

    private void processFounderExpiry(OwnerTrialLifecycle lifecycle, User owner, OffsetDateTime now) {
        LaunchCampaignBeneficiary founder = latestFounder(owner.getId());
        if (founder == null) {
            suspendAccess(lifecycle, owner, now, "El beneficio de lanzamiento ya no está disponible. Contrata un plan para publicar.");
            return;
        }
        if (founder.getStatus() == LaunchCampaignBeneficiaryStatus.REVOKED) {
            suspendAccess(lifecycle, owner, now, "El beneficio de lanzamiento fue revocado. Contrata un plan para publicar.");
            return;
        }
        OffsetDateTime graceUntil = founder.getExpiresAt().plusDays(Math.max(0, lifecycle.getGraceDaysSnapshot()));
        if (!graceUntil.isAfter(now)) {
            suspendAccess(lifecycle, owner, now,
                    "Finalizó tu beneficio Fundador y su período de gracia. Contrata un plan para volver a publicar tus pensiones.");
        }
    }

    private void suspendAccess(OwnerTrialLifecycle lifecycle, User owner, OffsetDateTime now, String message) {
        List<Pension> published = pensions.findPublishedForResponsibleUser(owner.getId());
        for (Pension pension : published) {
            pension.setStatus(PensionStatus.PAUSED);
            pension.setCommercialPauseReason(COMMERCIAL_PAUSE_REASON);
            pension.setCommercialPausedAt(now);
        }
        if (!published.isEmpty()) pensions.saveAll(published);
        lifecycle.setAccessSuspendedAt(now);
        lifecycles.save(lifecycle);

        String title = "Tus publicaciones quedaron pausadas";
        String dedupKey = "owner-access-paused:" + lifecycle.getId() + ":" + now.toLocalDate();
        notifyOnce(owner, NotificationType.OWNER_ACCESS_PAUSED, title, message, dedupKey);
    }

    private void reactivateCommerciallyPaused(User owner, OffsetDateTime now) {
        for (Pension pension : pensions.findCommerciallyPausedForResponsibleUser(owner.getId(), COMMERCIAL_PAUSE_REASON)) {
            pension.setCommercialPauseReason(null);
            pension.setCommercialPausedAt(null);
            if (Boolean.TRUE.equals(pension.getModerationBlocked())) {
                pensions.save(pension);
                continue;
            }
            try {
                publication.requirePublishable(pension);
                pension.setStatus(PensionStatus.PUBLISHED);
            } catch (ResponseStatusException ex) {
                // The owner can repair the publication manually; payment must never bypass publication quality rules.
            }
            pensions.save(pension);
        }
    }

    private OwnerTrialLifecycle startTrial(User user,
                                           Pension source,
                                           PlanVersion trialPlan,
                                           OwnerTrialSettings settings,
                                           OffsetDateTime now) {
        int durationDays = settings.getDurationDays();
        int graceDays = settings.getGraceDays();
        OffsetDateTime trialExpiresAt = now.plusDays(durationDays);
        OwnerTrialLifecycle lifecycle = lifecycles.save(OwnerTrialLifecycle.builder()
                .user(user)
                .consumptionReason(OwnerTrialConsumptionReason.TRIAL_STARTED)
                .sourcePension(source)
                .trialPlanVersion(trialPlan)
                .consumedAt(now)
                .trialStartedAt(now)
                .trialExpiresAt(trialExpiresAt)
                .graceExpiresAt(trialExpiresAt.plusDays(graceDays))
                .durationDaysSnapshot(durationDays)
                .graceDaysSnapshot(graceDays)
                .build());
        notifyOnce(user, NotificationType.OWNER_TRIAL_STARTED,
                "Comenzó tu prueba gratuita",
                "Tienes " + durationDays + " días para usar Pensiones como propietario. Finaliza el "
                        + formatDate(trialExpiresAt) + ". Después tendrás " + graceDays
                        + " días de gracia para elegir un plan.",
                "owner-trial-started:" + lifecycle.getId());
        return lifecycle;
    }

    private void notifyTrialReminder(OwnerTrialLifecycle lifecycle, User owner, int milestone, long daysRemaining) {
        long visibleDays = Math.max(1, daysRemaining);
        String title = visibleDays <= 3
                ? "Tu prueba gratuita termina en " + visibleDays + " días"
                : "Te quedan " + visibleDays + " días de prueba gratuita";
        String message = "Tu prueba gratuita termina el " + formatDate(lifecycle.getTrialExpiresAt())
                + ". Elige un plan para mantener tus publicaciones visibles sin interrupciones.";
        notifyOnce(owner, NotificationType.OWNER_TRIAL_EXPIRING, title, message,
                "owner-trial:" + lifecycle.getId() + ":reminder-" + milestone + ":"
                        + lifecycle.getTrialExpiresAt().toInstant().getEpochSecond());
    }

    private void notifyTrialGrace(OwnerTrialLifecycle lifecycle, User owner) {
        String title = "Tu prueba gratuita finalizó";
        String message = "Estás en el período de gracia hasta el " + formatDate(lifecycle.getGraceExpiresAt())
                + ". Contrata un plan antes de esa fecha para evitar que tus publicaciones sean pausadas.";
        notifyOnce(owner, NotificationType.OWNER_TRIAL_GRACE, title, message,
                "owner-trial:" + lifecycle.getId() + ":grace:"
                        + lifecycle.getGraceExpiresAt().toInstant().getEpochSecond());
    }

    private void notifyOnce(User owner, NotificationType type, String title, String message, String dedupKey) {
        if (!notifications.createOnce(owner, type, title, message, COMMERCIAL_PATH, dedupKey)) return;
        if (owner.getEmail() == null || owner.getEmail().isBlank()) return;
        try {
            mail.sendOwnerAccessNotice(owner.getEmail(), owner.getName(), title, message, absoluteCommercialLink());
        } catch (RuntimeException ex) {
            log.warn("No se pudo encolar aviso de acceso comercial. userId={}, type={}, error={}",
                    owner.getId(), type, ex.getMessage());
        }
    }

    private boolean hasActiveSubscription(Long userId, OffsetDateTime now) {
        return !subscriptions.findEffectiveActiveDetailed(userId, now, SubscriptionStatus.ACTIVE).isEmpty();
    }

    private boolean hasActiveFounderBenefit(Long userId, OffsetDateTime now) {
        return founderBenefits.findEffectiveDetailed(
                userId,
                FounderLaunchCampaignService.FOUNDER_CAMPAIGN_CODE,
                LaunchCampaignBeneficiaryStatus.ACTIVE,
                now
        ).isPresent();
    }

    private boolean hasFounderHistory(Long userId) {
        return latestFounder(userId) != null;
    }

    private LaunchCampaignBeneficiary latestFounder(Long userId) {
        List<LaunchCampaignBeneficiary> history = founderBenefits.findHistoryDetailed(
                userId, FounderLaunchCampaignService.FOUNDER_CAMPAIGN_CODE);
        return history.isEmpty() ? null : history.get(0);
    }

    private boolean isFounderDecision(FounderLaunchCampaignService.GrantResult result) {
        if (result == null) return false;
        return result.decision() == FounderLaunchCampaignService.GrantDecision.GRANTED
                || result.decision() == FounderLaunchCampaignService.GrantDecision.ALREADY_BENEFICIARY;
    }

    private PlanVersion requireUsableTrialPlan(OwnerTrialSettings settings, OffsetDateTime now) {
        PlanVersion version = settings.getTrialPlanVersion();
        if (version == null || version.getPlan() == null) {
            throw new MonetizationConfigurationException(
                    "OWNER_TRIAL_PLAN_NOT_CONFIGURED", "Selecciona una versión de plan para la prueba gratuita.");
        }
        if (!version.getPlan().isActive() || version.getStatus() != PlanVersionStatus.PUBLISHED
                || version.getEffectiveFrom() == null || now.isBefore(version.getEffectiveFrom())
                || (version.getEffectiveUntil() != null && !now.isBefore(version.getEffectiveUntil()))) {
            throw new MonetizationConfigurationException(
                    "OWNER_TRIAL_PLAN_NOT_EFFECTIVE", "La versión configurada para la prueba gratuita no está publicada y vigente.");
        }
        return version;
    }

    private int trialMilestone(long daysRemaining) {
        if (daysRemaining <= 0 || daysRemaining > 30) return 0;
        if (daysRemaining <= 3) return 3;
        if (daysRemaining <= 7) return 7;
        if (daysRemaining <= 14) return 14;
        return 30;
    }

    private String formatDate(OffsetDateTime value) {
        if (value == null) return "—";
        return value.atZoneSameInstant(businessZone).toLocalDate().format(dateFormatter);
    }

    private String absoluteCommercialLink() {
        String base = appProperties.getFrontendUrl();
        if (base == null || base.isBlank()) base = "http://localhost:5173";
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + COMMERCIAL_PATH;
    }

    private ZoneId safeZone(String value) {
        try {
            return ZoneId.of(value == null || value.isBlank() ? "America/Montevideo" : value.trim());
        } catch (RuntimeException ex) {
            return ZoneId.of("America/Montevideo");
        }
    }

    public record FounderGrace(boolean active,
                               LaunchCampaignBeneficiary benefit,
                               OffsetDateTime graceExpiresAt,
                               int graceDays) {
        static FounderGrace none() {
            return new FounderGrace(false, null, null, 0);
        }
    }
}
