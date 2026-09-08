package uy.pensiones.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import uy.pensiones.enums.AdminAuditAction;
import uy.pensiones.enums.AdminAuditEntityType;
import uy.pensiones.enums.OwnerTrialConsumptionReason;
import uy.pensiones.enums.PlanVersionStatus;
import uy.pensiones.model.BackofficeUser;
import uy.pensiones.model.OwnerTrialSettings;
import uy.pensiones.model.PlanVersion;
import uy.pensiones.repo.OwnerTrialLifecycleRepository;
import uy.pensiones.repo.OwnerTrialSettingsRepository;
import uy.pensiones.repo.PlanVersionRepository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class AdminOwnerTrialService {

    private static final int MAX_DURATION_DAYS = 3_650;
    private static final int MAX_GRACE_DAYS = 90;
    private static final String TRIAL_PLAN_CODE = "FREE";

    private final OwnerTrialSettingsRepository settings;
    private final OwnerTrialLifecycleRepository lifecycles;
    private final PlanVersionRepository planVersions;
    private final OwnerTrialLifecycleService lifecycleService;
    private final AdminAuditService audit;

    public AdminOwnerTrialService(OwnerTrialSettingsRepository settings,
                                  OwnerTrialLifecycleRepository lifecycles,
                                  PlanVersionRepository planVersions,
                                  OwnerTrialLifecycleService lifecycleService,
                                  AdminAuditService audit) {
        this.settings = settings;
        this.lifecycles = lifecycles;
        this.planVersions = planVersions;
        this.lifecycleService = lifecycleService;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public TrialSettingsDTO detail() {
        OwnerTrialSettings value = settings.findDetailedById(OwnerTrialLifecycleService.SETTINGS_ID)
                .orElseThrow(() -> new MonetizationConfigurationException(
                        "OWNER_TRIAL_SETTINGS_MISSING", "No existe la configuración de prueba gratuita para propietarios."));
        return dto(value, 0);
    }

    @Transactional
    public TrialSettingsDTO update(boolean enabled,
                                   int durationDays,
                                   int graceDays,
                                   Long trialPlanVersionId,
                                   String rawReason,
                                   BackofficeUser actor) {
        OwnerTrialSettings value = settings.findByIdForUpdate(OwnerTrialLifecycleService.SETTINGS_ID)
                .orElseThrow(() -> new MonetizationConfigurationException(
                        "OWNER_TRIAL_SETTINGS_MISSING", "No existe la configuración de prueba gratuita para propietarios."));
        String reason = audit.requireReason(rawReason);
        validateDuration(durationDays, graceDays);

        if (value.isEnabled() && !enabled) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "La política de prueba gratuita ya fue activada y no puede volver al modo FREE permanente. "
                            + "Si necesitas detener cobros temporalmente utiliza los controles maestros de monetización/pagos.");
        }

        PlanVersion trialPlan = null;
        if (trialPlanVersionId != null) {
            trialPlan = planVersions.findById(trialPlanVersionId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                            "Versión de plan para la prueba gratuita no encontrada"));
            validateTrialPlan(trialPlan, OffsetDateTime.now(ZoneOffset.UTC));
        }
        if (enabled && trialPlan == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Selecciona una versión FREE publicada y vigente antes de habilitar la prueba gratuita");
        }

        Map<String, Object> before = snapshot(value);
        boolean activating = !value.isEnabled() && enabled;
        value.setEnabled(enabled);
        value.setDurationDays(durationDays);
        value.setGraceDays(graceDays);
        value.setTrialPlanVersion(trialPlan);
        value = settings.save(value);

        int backfilledOwners = activating ? lifecycleService.backfillPublishedOwners(value, OffsetDateTime.now(ZoneOffset.UTC)) : 0;
        Map<String, Object> after = snapshot(value);
        after.put("backfilledOwners", backfilledOwners);
        audit.record(actor, AdminAuditAction.ADMIN_UPDATE_OWNER_TRIAL_SETTINGS,
                AdminAuditEntityType.OWNER_TRIAL_SETTINGS, value.getId(), before, after, reason);
        return dto(value, backfilledOwners);
    }

    private void validateDuration(int durationDays, int graceDays) {
        if (durationDays < 1 || durationDays > MAX_DURATION_DAYS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La prueba gratuita debe durar entre 1 y " + MAX_DURATION_DAYS + " días");
        }
        if (graceDays < 0 || graceDays > MAX_GRACE_DAYS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El período de gracia debe estar entre 0 y " + MAX_GRACE_DAYS + " días");
        }
    }

    private void validateTrialPlan(PlanVersion version, OffsetDateTime now) {
        if (version.getPlan() == null || !TRIAL_PLAN_CODE.equalsIgnoreCase(version.getPlan().getCode())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La prueba gratuita debe utilizar una versión del plan FREE");
        }
        if (!version.getPlan().isActive() || version.getStatus() != PlanVersionStatus.PUBLISHED
                || version.getEffectiveFrom() == null || now.isBefore(version.getEffectiveFrom())
                || (version.getEffectiveUntil() != null && !now.isBefore(version.getEffectiveUntil()))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "La versión FREE seleccionada debe estar publicada, activa y vigente");
        }
    }

    private TrialSettingsDTO dto(OwnerTrialSettings value, int backfilledOwners) {
        PlanVersion version = value.getTrialPlanVersion();
        return new TrialSettingsDTO(
                value.isEnabled(), value.getDurationDays(), value.getGraceDays(),
                version == null ? null : version.getId(),
                version == null || version.getPlan() == null ? null : version.getPlan().getCode(),
                version == null || version.getPlan() == null ? null : version.getPlan().getName(),
                version == null ? null : version.getVersion(),
                lifecycles.countByConsumptionReason(OwnerTrialConsumptionReason.TRIAL_STARTED),
                lifecycles.countByConsumptionReason(OwnerTrialConsumptionReason.FOUNDER_GRANTED),
                lifecycles.countByConsumptionReason(OwnerTrialConsumptionReason.PAID_DIRECT),
                backfilledOwners,
                value.getUpdatedAt()
        );
    }

    private Map<String, Object> snapshot(OwnerTrialSettings value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", value.isEnabled());
        result.put("durationDays", value.getDurationDays());
        result.put("graceDays", value.getGraceDays());
        result.put("trialPlanVersionId", value.getTrialPlanVersion() == null ? null : value.getTrialPlanVersion().getId());
        return result;
    }

    public record TrialSettingsDTO(
            boolean enabled,
            int durationDays,
            int graceDays,
            Long trialPlanVersionId,
            String trialPlanCode,
            String trialPlanName,
            Integer trialPlanVersion,
            long trialsStarted,
            long foundersConsumed,
            long paidBeforeTrial,
            int backfilledOwners,
            OffsetDateTime updatedAt
    ) {}
}
