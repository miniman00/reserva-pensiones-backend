package uy.pensiones.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;
import uy.pensiones.enums.PlanVersionStatus;
import uy.pensiones.model.BackofficeUser;
import uy.pensiones.model.OwnerTrialSettings;
import uy.pensiones.model.Plan;
import uy.pensiones.model.PlanVersion;
import uy.pensiones.repo.OwnerTrialLifecycleRepository;
import uy.pensiones.repo.OwnerTrialSettingsRepository;
import uy.pensiones.repo.PlanVersionRepository;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminOwnerTrialServiceTest {

    @Mock OwnerTrialSettingsRepository settings;
    @Mock OwnerTrialLifecycleRepository lifecycles;
    @Mock PlanVersionRepository planVersions;
    @Mock OwnerTrialLifecycleService lifecycleService;
    @Mock AdminAuditService audit;

    AdminOwnerTrialService service;

    @BeforeEach
    void setUp() {
        service = new AdminOwnerTrialService(settings, lifecycles, planVersions, lifecycleService, audit);
        lenient().when(audit.requireReason(any())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(settings.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void firstActivationRequiresEffectiveFreeVersionAndBackfillsExistingPublishedOwners() {
        OffsetDateTime now = OffsetDateTime.now();
        OwnerTrialSettings config = OwnerTrialSettings.builder()
                .id((short) 1).enabled(false).durationDays(90).graceDays(7).build();
        Plan free = Plan.builder().id(1L).code("FREE").name("Prueba gratuita").active(true).build();
        PlanVersion version = PlanVersion.builder().id(11L).plan(free).version(1)
                .status(PlanVersionStatus.PUBLISHED).effectiveFrom(now.minusDays(1)).build();
        when(settings.findByIdForUpdate((short) 1)).thenReturn(Optional.of(config));
        when(planVersions.findById(11L)).thenReturn(Optional.of(version));
        when(lifecycleService.backfillPublishedOwners(eq(config), any())).thenReturn(3);

        var result = service.update(true, 90, 7, 11L, "Activación inicial", BackofficeUser.builder().id(5L).build());

        assertThat(result.enabled()).isTrue();
        assertThat(result.durationDays()).isEqualTo(90);
        assertThat(result.graceDays()).isEqualTo(7);
        assertThat(result.trialPlanVersionId()).isEqualTo(11L);
        assertThat(result.backfilledOwners()).isEqualTo(3);
        verify(audit).record(any(), any(), any(), eq((short) 1), any(), any(), eq("Activación inicial"));
    }

    @Test
    void activatedPolicyCannotReturnToPermanentFreeMode() {
        OwnerTrialSettings config = OwnerTrialSettings.builder()
                .id((short) 1).enabled(true).durationDays(90).graceDays(7).build();
        when(settings.findByIdForUpdate((short) 1)).thenReturn(Optional.of(config));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.update(false, 90, 7, null, "Intento de desactivar", BackofficeUser.builder().id(5L).build()));

        assertThat(error.getStatusCode().value()).isEqualTo(409);
        verify(settings, never()).save(any());
    }
}
