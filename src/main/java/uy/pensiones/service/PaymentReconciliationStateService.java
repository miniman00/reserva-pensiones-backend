package uy.pensiones.service;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import uy.pensiones.enums.*;
import uy.pensiones.model.*;
import uy.pensiones.payment.PaymentRuntimeConfigurationService;
import uy.pensiones.repo.PaymentReconciliationRunRepository;
import uy.pensiones.repo.PaymentSettingsRepository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Service
public class PaymentReconciliationStateService {
    private static final int LEASE_MINUTES = 30;
    private final PaymentSettingsRepository settings;
    private final PaymentReconciliationRunRepository runs;
    private final AdminAuditService audit;

    public PaymentReconciliationStateService(PaymentSettingsRepository settings,
                                             PaymentReconciliationRunRepository runs,
                                             AdminAuditService audit) {
        this.settings = settings; this.runs = runs; this.audit = audit;
    }

    @Transactional
    public StartRun tryStartAutomatic() {
        PaymentSettings s = lockSettings();
        OffsetDateTime now = now();
        if (leaseActive(s, now)) return null;
        recoverExpiredLease(s, now);
        if (!s.isAutomaticReconciliationEnabled()) return null;
        if (s.getReconciliationLastStartedAt() != null
                && s.getReconciliationLastStartedAt().plusMinutes(s.getReconciliationIntervalMinutes()).isAfter(now)) return null;
        return start(s, PaymentReconciliationTrigger.AUTOMATIC, null, now);
    }

    @Transactional
    public StartRun startManual(BackofficeUser actor, String rawReason) {
        String reason = audit.requireReason(rawReason);
        PaymentSettings s = lockSettings();
        OffsetDateTime now = now();
        if (leaseActive(s, now)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Ya existe una conciliación en curso");
        recoverExpiredLease(s, now);
        StartRun started = start(s, PaymentReconciliationTrigger.MANUAL, actor, now);
        audit.record(actor, AdminAuditAction.ADMIN_RUN_PAYMENT_RECONCILIATION,
                AdminAuditEntityType.PAYMENT_RECONCILIATION_RUN, started.runId(), null,
                java.util.Map.of("status", "RUNNING", "trigger", "MANUAL"), reason);
        return started;
    }

    private StartRun start(PaymentSettings s, PaymentReconciliationTrigger trigger, BackofficeUser actor, OffsetDateTime now) {
        String owner = UUID.randomUUID().toString();
        s.setReconciliationLeaseOwner(owner);
        s.setReconciliationLeaseUntil(now.plusMinutes(LEASE_MINUTES));
        s.setReconciliationLastStartedAt(now);
        settings.save(s);
        PaymentReconciliationRun run = runs.save(PaymentReconciliationRun.builder()
                .triggerType(trigger).status(PaymentReconciliationRunStatus.RUNNING)
                .startedByBackoffice(actor).leaseOwner(owner).startedAt(now).build());
        return new StartRun(run.getId(), owner, Math.max(1, s.getReconciliationIntervalMinutes()),
                Math.min(200, Math.max(1, s.getReconciliationBatchSize())));
    }

    @Transactional
    public boolean heartbeat(Long runId, String owner) {
        PaymentSettings s = lockSettings();
        if (owner == null || !owner.equals(s.getReconciliationLeaseOwner()) || s.getReconciliationLeaseUntil() == null) return false;
        s.setReconciliationLeaseUntil(now().plusMinutes(LEASE_MINUTES));
        settings.save(s);
        return true;
    }

    @Transactional
    public void finish(Long runId, String owner, RunStats stats) {
        PaymentSettings s = lockSettings();
        PaymentReconciliationRun run = runs.findByIdForUpdate(runId).orElseThrow();
        OffsetDateTime now = now();
        applyStats(run, stats);
        if (!owner.equals(s.getReconciliationLeaseOwner())) {
            run.setStatus(PaymentReconciliationRunStatus.FAILED);
            run.setCompletedAt(now);
            run.setErrorsCount(Math.max(1, stats.errors()));
            run.setSummaryMessage(trim("La ejecución perdió el lease antes de finalizar · " + stats.message(), 1000));
            runs.save(run);
            return;
        }
        PaymentReconciliationRunStatus status = stats.errors() == 0 ? PaymentReconciliationRunStatus.SUCCEEDED
                : (stats.successfulChecks() == 0 ? PaymentReconciliationRunStatus.FAILED : PaymentReconciliationRunStatus.PARTIAL);
        run.setStatus(status); run.setCompletedAt(now);
        run.setSummaryMessage(trim(stats.message(), 1000)); runs.save(run);

        s.setReconciliationLeaseOwner(null); s.setReconciliationLeaseUntil(null);
        s.setReconciliationLastCompletedAt(now);
        s.setReconciliationLastSuccess(status == PaymentReconciliationRunStatus.SUCCEEDED);
        s.setReconciliationLastMessage(trim(stats.message(), 500));
        settings.save(s);
    }

    @Transactional(readOnly = true)
    public StatusDTO status() {
        PaymentSettings s = settings.findById(PaymentRuntimeConfigurationService.SETTINGS_ID).orElseThrow();
        List<PaymentReconciliationRun> recentEntities = runs.findAllByOrderByStartedAtDescIdDesc(PageRequest.of(0, 12));
        List<RunDTO> recent = recentEntities.stream().map(this::dto).toList();
        OffsetDateTime now = now();
        HealthSnapshot health = health(s, recentEntities, now);
        return new StatusDTO(s.isAutomaticReconciliationEnabled(), s.getReconciliationIntervalMinutes(),
                s.getReconciliationBatchSize(), health.running(), health.health(), health.message(),
                health.nextAutomaticAt(), health.staleAfterAt(), s.getReconciliationLeaseUntil(),
                health.consecutiveProblemRuns(), health.lastDurationSeconds(), s.getReconciliationLastStartedAt(),
                s.getReconciliationLastCompletedAt(), s.getReconciliationLastSuccess(), s.getReconciliationLastMessage(), recent);
    }

    @Transactional(readOnly = true)
    public RunDTO run(Long id) {
        return dto(runs.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ejecución no encontrada")));
    }

    private void recoverExpiredLease(PaymentSettings s, OffsetDateTime now) {
        String expiredOwner = s.getReconciliationLeaseOwner();
        if (expiredOwner == null || (s.getReconciliationLeaseUntil() != null && s.getReconciliationLeaseUntil().isAfter(now))) return;
        String message = "Ejecución recuperada como fallida porque expiró el lease del proceso anterior";
        runs.findByLeaseOwnerAndStatusForUpdate(expiredOwner, PaymentReconciliationRunStatus.RUNNING).ifPresent(run -> {
            run.setStatus(PaymentReconciliationRunStatus.FAILED);
            run.setCompletedAt(now); run.setErrorsCount(Math.max(1, run.getErrorsCount()));
            run.setSummaryMessage(message);
            runs.save(run);
        });
        s.setReconciliationLeaseOwner(null); s.setReconciliationLeaseUntil(null);
        s.setReconciliationLastCompletedAt(now);
        s.setReconciliationLastSuccess(false);
        s.setReconciliationLastMessage(trim(message, 500));
        settings.save(s);
    }

    private HealthSnapshot health(PaymentSettings s, List<PaymentReconciliationRun> recent, OffsetDateTime now) {
        boolean running = leaseActive(s, now);
        boolean leasePresent = s.getReconciliationLeaseOwner() != null;
        boolean leaseExpired = leasePresent && (s.getReconciliationLeaseUntil() == null || !s.getReconciliationLeaseUntil().isAfter(now));
        int interval = Math.max(1, s.getReconciliationIntervalMinutes());
        OffsetDateTime nextAutomaticAt = s.isAutomaticReconciliationEnabled() && s.getReconciliationLastStartedAt() != null
                ? s.getReconciliationLastStartedAt().plusMinutes(interval) : null;
        OffsetDateTime staleReference = s.getReconciliationLastCompletedAt() != null
                ? s.getReconciliationLastCompletedAt() : s.getUpdatedAt();
        OffsetDateTime staleAfterAt = s.isAutomaticReconciliationEnabled() && staleReference != null
                ? staleReference.plusMinutes((long) interval * 3L) : null;
        boolean stale = s.isAutomaticReconciliationEnabled() && !running && !leaseExpired
                && staleAfterAt != null && !staleAfterAt.isAfter(now);

        PaymentReconciliationHealth health;
        String message;
        if (leaseExpired) {
            health = PaymentReconciliationHealth.STUCK;
            message = "El lease de la conciliación venció y está pendiente de recuperación por el scheduler.";
        } else if (running) {
            health = PaymentReconciliationHealth.RUNNING;
            message = "Hay una conciliación en curso con lease vigente.";
        } else if (!s.isAutomaticReconciliationEnabled()) {
            health = PaymentReconciliationHealth.DISABLED;
            message = "La conciliación automática está deshabilitada; las ejecuciones manuales siguen disponibles.";
        } else if (stale) {
            health = PaymentReconciliationHealth.STALE;
            message = "La conciliación automática no completa una ejecución dentro de la cadencia esperada.";
        } else if (Boolean.FALSE.equals(s.getReconciliationLastSuccess())) {
            health = PaymentReconciliationHealth.DEGRADED;
            message = "La última conciliación terminó con incidencias; revisar el historial y las alertas operativas.";
        } else {
            health = PaymentReconciliationHealth.HEALTHY;
            message = s.getReconciliationLastCompletedAt() == null
                    ? "Conciliación habilitada y pendiente de su primera ejecución."
                    : "La última conciliación terminó correctamente y la cadencia está dentro de lo esperado.";
        }

        int consecutiveProblemRuns = 0;
        for (PaymentReconciliationRun run : recent) {
            if (run.getStatus() == PaymentReconciliationRunStatus.RUNNING) continue;
            if (run.getStatus() == PaymentReconciliationRunStatus.SUCCEEDED) break;
            if (run.getStatus() == PaymentReconciliationRunStatus.FAILED
                    || run.getStatus() == PaymentReconciliationRunStatus.PARTIAL) consecutiveProblemRuns++;
            else break;
        }
        Long lastDurationSeconds = recent.stream()
                .filter(r -> r.getCompletedAt() != null && r.getStartedAt() != null)
                .findFirst()
                .map(r -> Math.max(0L, java.time.Duration.between(r.getStartedAt(), r.getCompletedAt()).getSeconds()))
                .orElse(null);
        return new HealthSnapshot(health, message, running, nextAutomaticAt, staleAfterAt,
                consecutiveProblemRuns, lastDurationSeconds);
    }

    private void applyStats(PaymentReconciliationRun run, RunStats stats) {
        run.setPaymentsChecked(stats.paymentsChecked()); run.setPaymentsChanged(stats.paymentsChanged());
        run.setRefundsChecked(stats.refundsChecked()); run.setRefundsChanged(stats.refundsChanged());
        run.setChargebacksChecked(stats.chargebacksChecked()); run.setChargebacksChanged(stats.chargebacksChanged());
        run.setErrorsCount(stats.errors());
    }

    private RunDTO dto(PaymentReconciliationRun r) {
        BackofficeUser actor = r.getStartedByBackoffice();
        return new RunDTO(r.getId(), r.getTriggerType(), r.getStatus(), actor == null ? null : actor.getDisplayName(),
                r.getStartedAt(), r.getCompletedAt(), r.getPaymentsChecked(), r.getPaymentsChanged(),
                r.getRefundsChecked(), r.getRefundsChanged(), r.getChargebacksChecked(), r.getChargebacksChanged(),
                r.getErrorsCount(), r.getSummaryMessage());
    }
    private PaymentSettings lockSettings() { return settings.findByIdForUpdate(PaymentRuntimeConfigurationService.SETTINGS_ID).orElseThrow(); }
    private boolean leaseActive(PaymentSettings s, OffsetDateTime now) { return s.getReconciliationLeaseOwner() != null && s.getReconciliationLeaseUntil() != null && s.getReconciliationLeaseUntil().isAfter(now); }
    private OffsetDateTime now() { return OffsetDateTime.now(ZoneOffset.UTC); }
    private String trim(String v, int max) { if (v == null) return null; return v.length() <= max ? v : v.substring(0,max); }

    public record StartRun(Long runId, String owner, int intervalMinutes, int batchSize) {}

    public record RunStats(int paymentsChecked, int paymentsChanged, int refundsChecked, int refundsChanged,
                           int chargebacksChecked, int chargebacksChanged, int errors, int successfulChecks, String message) {}

    private record HealthSnapshot(PaymentReconciliationHealth health, String message, boolean running,
                                  OffsetDateTime nextAutomaticAt, OffsetDateTime staleAfterAt,
                                  int consecutiveProblemRuns, Long lastDurationSeconds) {}

    public record StatusDTO(boolean enabled, int intervalMinutes, int batchSize, boolean running,
                            PaymentReconciliationHealth health, String healthMessage,
                            OffsetDateTime nextAutomaticAt, OffsetDateTime staleAfterAt, OffsetDateTime leaseUntil,
                            int consecutiveProblemRuns, Long lastDurationSeconds,
                            OffsetDateTime lastStartedAt, OffsetDateTime lastCompletedAt, Boolean lastSuccess,
                            String lastMessage, List<RunDTO> recentRuns) {}
    public record RunDTO(Long id, PaymentReconciliationTrigger trigger, PaymentReconciliationRunStatus status,
                         String startedBy, OffsetDateTime startedAt, OffsetDateTime completedAt,
                         int paymentsChecked, int paymentsChanged, int refundsChecked, int refundsChanged,
                         int chargebacksChecked, int chargebacksChanged, int errors, String summaryMessage) {}
}
