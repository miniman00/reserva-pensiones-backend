package uy.pensiones.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import uy.pensiones.enums.*;
import uy.pensiones.model.*;
import uy.pensiones.repo.PaymentReconciliationRunRepository;
import uy.pensiones.repo.PaymentSettingsRepository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PaymentReconciliationStateServiceTest {
    private final PaymentSettingsRepository settings = mock(PaymentSettingsRepository.class);
    private final PaymentReconciliationRunRepository runs = mock(PaymentReconciliationRunRepository.class);
    private final AdminAuditService audit = mock(AdminAuditService.class);
    private PaymentReconciliationStateService service;
    private PaymentSettings global;
    private BackofficeUser actor;

    @BeforeEach
    void setUp() {
        service = new PaymentReconciliationStateService(settings, runs, audit);
        global = PaymentSettings.builder().id((short) 1).automaticReconciliationEnabled(false)
                .reconciliationIntervalMinutes(5).reconciliationBatchSize(50).build();
        actor = BackofficeUser.builder().id(9L).username("admin").displayName("Admin").role(BackofficeRole.ADMIN).build();
        when(settings.findByIdForUpdate((short) 1)).thenReturn(Optional.of(global));
        when(settings.findById((short) 1)).thenReturn(Optional.of(global));
        when(runs.findAllByOrderByStartedAtDescIdDesc(any())).thenReturn(List.of());
        when(settings.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(runs.save(any())).thenAnswer(invocation -> {
            PaymentReconciliationRun run = invocation.getArgument(0);
            if (run.getId() == null) run.setId(77L);
            return run;
        });
    }

    @Test
    void automaticRunDoesNotStartWhenFeatureIsDisabled() {
        assertThat(service.tryStartAutomatic()).isNull();
        verify(runs, never()).save(any());
    }

    @Test
    void activeLeasePreventsAnotherAutomaticRun() {
        global.setAutomaticReconciliationEnabled(true);
        global.setReconciliationLeaseOwner("active-owner");
        global.setReconciliationLeaseUntil(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5));

        assertThat(service.tryStartAutomatic()).isNull();

        verify(runs, never()).save(any());
        assertThat(global.getReconciliationLeaseOwner()).isEqualTo("active-owner");
    }

    @Test
    void manualRunAcquiresDatabaseLeaseEvenWhenAutomaticModeIsDisabled() {
        when(audit.requireReason("Revisión preventiva")).thenReturn("Revisión preventiva");

        var started = service.startManual(actor, "Revisión preventiva");

        assertThat(started.runId()).isEqualTo(77L);
        assertThat(started.batchSize()).isEqualTo(50);
        assertThat(global.getReconciliationLeaseOwner()).isNotBlank();
        assertThat(global.getReconciliationLeaseUntil()).isNotNull();
        verify(audit).record(eq(actor), eq(AdminAuditAction.ADMIN_RUN_PAYMENT_RECONCILIATION),
                eq(AdminAuditEntityType.PAYMENT_RECONCILIATION_RUN), eq(77L), isNull(), any(), eq("Revisión preventiva"));
    }

    @Test
    void manualRunIsRejectedWhileAnotherLeaseIsActive() {
        global.setReconciliationLeaseOwner("active-owner");
        global.setReconciliationLeaseUntil(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5));
        when(audit.requireReason("Control manual")).thenReturn("Control manual");

        assertThatThrownBy(() -> service.startManual(actor, "Control manual"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value()).isEqualTo(409));

        verify(runs, never()).save(any());
    }

    @Test
    void expiredLeaseIsRecoveredEvenWhenAutomaticModeIsDisabled() {
        OffsetDateTime oldStart = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(40);
        global.setReconciliationLeaseOwner("stale-owner");
        global.setReconciliationLeaseUntil(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1));
        global.setReconciliationLastStartedAt(oldStart);
        global.setReconciliationLastSuccess(true);
        global.setReconciliationLastMessage("Anterior OK");
        PaymentReconciliationRun stale = PaymentReconciliationRun.builder().id(55L)
                .triggerType(PaymentReconciliationTrigger.AUTOMATIC).status(PaymentReconciliationRunStatus.RUNNING)
                .leaseOwner("stale-owner").startedAt(oldStart).build();
        when(runs.findByLeaseOwnerAndStatusForUpdate("stale-owner", PaymentReconciliationRunStatus.RUNNING))
                .thenReturn(Optional.of(stale));

        assertThat(service.tryStartAutomatic()).isNull();

        assertThat(stale.getStatus()).isEqualTo(PaymentReconciliationRunStatus.FAILED);
        assertThat(stale.getCompletedAt()).isNotNull();
        assertThat(stale.getErrorsCount()).isEqualTo(1);
        assertThat(stale.getSummaryMessage()).contains("expiró el lease");
        assertThat(global.getReconciliationLeaseOwner()).isNull();
        assertThat(global.getReconciliationLeaseUntil()).isNull();
        assertThat(global.getReconciliationLastCompletedAt()).isNotNull();
        assertThat(global.getReconciliationLastSuccess()).isFalse();
        assertThat(global.getReconciliationLastMessage()).contains("expiró el lease");
        verify(settings, atLeastOnce()).save(global);
    }

    @Test
    void expiredLeaseCanBeRecoveredAndReplacedByANewAutomaticRun() {
        OffsetDateTime oldStart = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(40);
        global.setAutomaticReconciliationEnabled(true);
        global.setReconciliationLeaseOwner("stale-owner");
        global.setReconciliationLeaseUntil(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1));
        global.setReconciliationLastStartedAt(oldStart);
        PaymentReconciliationRun stale = PaymentReconciliationRun.builder().id(55L)
                .triggerType(PaymentReconciliationTrigger.AUTOMATIC).status(PaymentReconciliationRunStatus.RUNNING)
                .leaseOwner("stale-owner").startedAt(oldStart).build();
        when(runs.findByLeaseOwnerAndStatusForUpdate("stale-owner", PaymentReconciliationRunStatus.RUNNING))
                .thenReturn(Optional.of(stale));

        var started = service.tryStartAutomatic();

        assertThat(started).isNotNull();
        assertThat(started.runId()).isEqualTo(77L);
        assertThat(started.owner()).isNotEqualTo("stale-owner");
        assertThat(stale.getStatus()).isEqualTo(PaymentReconciliationRunStatus.FAILED);
        assertThat(global.getReconciliationLeaseOwner()).isEqualTo(started.owner());
        assertThat(global.getReconciliationLeaseUntil()).isAfter(OffsetDateTime.now(ZoneOffset.UTC));
    }

    @Test
    void staleHeartbeatCannotRenewLeaseOwnedByAnotherRun() {
        OffsetDateTime currentLease = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5);
        global.setReconciliationLeaseOwner("new-owner");
        global.setReconciliationLeaseUntil(currentLease);

        assertThat(service.heartbeat(77L, "old-owner")).isFalse();

        assertThat(global.getReconciliationLeaseOwner()).isEqualTo("new-owner");
        assertThat(global.getReconciliationLeaseUntil()).isEqualTo(currentLease);
        verify(settings, never()).save(any());
    }

    @Test
    void currentHeartbeatRenewsLease() {
        OffsetDateTime currentLease = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(1);
        global.setReconciliationLeaseOwner("owner");
        global.setReconciliationLeaseUntil(currentLease);

        assertThat(service.heartbeat(77L, "owner")).isTrue();

        assertThat(global.getReconciliationLeaseUntil()).isAfter(currentLease);
        verify(settings).save(global);
    }

    @Test
    void finishingAfterLeaseLossPreservesPartialStatsWithoutClearingNewLease() {
        global.setReconciliationLeaseOwner("new-owner");
        global.setReconciliationLeaseUntil(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5));
        PaymentReconciliationRun oldRun = PaymentReconciliationRun.builder().id(77L)
                .triggerType(PaymentReconciliationTrigger.AUTOMATIC).status(PaymentReconciliationRunStatus.RUNNING)
                .leaseOwner("old-owner").startedAt(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(2)).build();
        when(runs.findByIdForUpdate(77L)).thenReturn(Optional.of(oldRun));

        service.finish(77L, "old-owner", new PaymentReconciliationStateService.RunStats(
                2, 1, 1, 0, 0, 0, 1, 2, "Resultado parcial"));

        assertThat(oldRun.getStatus()).isEqualTo(PaymentReconciliationRunStatus.FAILED);
        assertThat(oldRun.getPaymentsChecked()).isEqualTo(2);
        assertThat(oldRun.getPaymentsChanged()).isEqualTo(1);
        assertThat(oldRun.getRefundsChecked()).isEqualTo(1);
        assertThat(oldRun.getErrorsCount()).isEqualTo(1);
        assertThat(oldRun.getSummaryMessage()).contains("perdió el lease").contains("Resultado parcial");
        assertThat(global.getReconciliationLeaseOwner()).isEqualTo("new-owner");
        assertThat(global.getReconciliationLeaseUntil()).isNotNull();
        verify(settings, never()).save(any());
    }

    @Test
    void finishUsesSuccessfulChecksToDistinguishPartialFromFailed() {
        global.setReconciliationLeaseOwner("owner");
        global.setReconciliationLeaseUntil(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5));
        PaymentReconciliationRun run = PaymentReconciliationRun.builder().id(77L)
                .triggerType(PaymentReconciliationTrigger.AUTOMATIC).status(PaymentReconciliationRunStatus.RUNNING)
                .leaseOwner("owner").startedAt(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1)).build();
        when(runs.findByIdForUpdate(77L)).thenReturn(Optional.of(run));

        service.finish(77L, "owner", new PaymentReconciliationStateService.RunStats(
                2, 1, 0, 0, 0, 0, 1, 1, "Un pago falló y otro terminó"));

        assertThat(run.getStatus()).isEqualTo(PaymentReconciliationRunStatus.PARTIAL);
        assertThat(global.getReconciliationLeaseOwner()).isNull();
        assertThat(global.getReconciliationLastSuccess()).isFalse();
        assertThat(global.getReconciliationLastMessage()).isEqualTo("Un pago falló y otro terminó");
    }
    @Test
    void statusReportsRunningManualLeaseEvenWhenAutomaticModeIsDisabled() {
        global.setAutomaticReconciliationEnabled(false);
        global.setReconciliationLeaseOwner("manual-owner");
        global.setReconciliationLeaseUntil(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5));

        var status = service.status();

        assertThat(status.running()).isTrue();
        assertThat(status.health()).isEqualTo(PaymentReconciliationHealth.RUNNING);
    }

    @Test
    void statusReportsExpiredLeaseAsStuck() {
        global.setAutomaticReconciliationEnabled(true);
        global.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(2));
        global.setReconciliationLeaseOwner("expired-owner");
        global.setReconciliationLeaseUntil(OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(30));

        var status = service.status();

        assertThat(status.running()).isFalse();
        assertThat(status.health()).isEqualTo(PaymentReconciliationHealth.STUCK);
        assertThat(status.healthMessage()).contains("lease");
        assertThat(status.leaseUntil()).isEqualTo(global.getReconciliationLeaseUntil());
    }

    @Test
    void statusReportsStaleWhenAutomaticCadenceIsMissed() {
        global.setAutomaticReconciliationEnabled(true);
        global.setReconciliationIntervalMinutes(5);
        global.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC).minusHours(1));
        global.setReconciliationLastCompletedAt(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(20));
        global.setReconciliationLastSuccess(true);

        var status = service.status();

        assertThat(status.health()).isEqualTo(PaymentReconciliationHealth.STALE);
        assertThat(status.staleAfterAt()).isBefore(OffsetDateTime.now(ZoneOffset.UTC));
        assertThat(status.healthMessage()).contains("cadencia");
    }

    @Test
    void statusExposesConsecutiveProblemRunsAndLastDuration() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        global.setAutomaticReconciliationEnabled(true);
        global.setUpdatedAt(now.minusMinutes(2));
        global.setReconciliationLastStartedAt(now.minusMinutes(1));
        global.setReconciliationLastCompletedAt(now.minusMinutes(1));
        global.setReconciliationLastSuccess(false);
        PaymentReconciliationRun failed = PaymentReconciliationRun.builder().id(3L)
                .triggerType(PaymentReconciliationTrigger.AUTOMATIC).status(PaymentReconciliationRunStatus.FAILED)
                .leaseOwner("a").startedAt(now.minusSeconds(90)).completedAt(now.minusSeconds(60)).build();
        PaymentReconciliationRun partial = PaymentReconciliationRun.builder().id(2L)
                .triggerType(PaymentReconciliationTrigger.MANUAL).status(PaymentReconciliationRunStatus.PARTIAL)
                .leaseOwner("b").startedAt(now.minusMinutes(4)).completedAt(now.minusMinutes(3)).build();
        PaymentReconciliationRun success = PaymentReconciliationRun.builder().id(1L)
                .triggerType(PaymentReconciliationTrigger.AUTOMATIC).status(PaymentReconciliationRunStatus.SUCCEEDED)
                .leaseOwner("c").startedAt(now.minusMinutes(7)).completedAt(now.minusMinutes(6)).build();
        when(runs.findAllByOrderByStartedAtDescIdDesc(any())).thenReturn(List.of(failed, partial, success));

        var status = service.status();

        assertThat(status.health()).isEqualTo(PaymentReconciliationHealth.DEGRADED);
        assertThat(status.consecutiveProblemRuns()).isEqualTo(2);
        assertThat(status.lastDurationSeconds()).isEqualTo(30L);
        assertThat(status.nextAutomaticAt()).isAfter(now);
    }

}
