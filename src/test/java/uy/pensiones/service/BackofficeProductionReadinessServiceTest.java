package uy.pensiones.service;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import uy.pensiones.enums.BackofficeRole;
import uy.pensiones.model.BackofficeUser;
import uy.pensiones.repo.BackofficeUserRepository;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class BackofficeProductionReadinessServiceTest {
    @Test
    void readyWhenProductionControlsAreClosedAndPrivilegedAccountsHaveMfa() {
        BackofficeUserRepository users = mock(BackofficeUserRepository.class);
        BackofficeSystemSuperAdminService system = mock(BackofficeSystemSuperAdminService.class);
        when(system.isConfigured()).thenReturn(true);
        when(users.findAllByOrderByDisplayNameAscUsernameAsc()).thenReturn(List.of(privileged("soporte", BackofficeRole.SUPER_ADMIN)));
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        var svc = new BackofficeProductionReadinessService(users, system, env,
                true, false, true, "Strict", Duration.ofHours(8), Duration.ofMinutes(30), true);
        assertThat(svc.evaluate().state()).isEqualTo(BackofficeProductionReadinessService.State.READY);
    }

    @Test
    void breakGlassLeftEnabledBlocksReadiness() {
        BackofficeUserRepository users = mock(BackofficeUserRepository.class);
        BackofficeSystemSuperAdminService system = mock(BackofficeSystemSuperAdminService.class);
        when(system.isConfigured()).thenReturn(true);
        when(users.findAllByOrderByDisplayNameAscUsernameAsc()).thenReturn(List.of(privileged("soporte", BackofficeRole.SUPER_ADMIN)));
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        var svc = new BackofficeProductionReadinessService(users, system, env,
                true, true, true, "Strict", Duration.ofHours(8), Duration.ofMinutes(30), true);
        assertThat(svc.evaluate().state()).isEqualTo(BackofficeProductionReadinessService.State.BLOCKED);
    }

    private BackofficeUser privileged(String username, BackofficeRole role) {
        return BackofficeUser.builder().id(1L).username(username).displayName(username).passwordHash("hash")
                .role(role).active(true).mfaSecretCiphertext("encrypted").mfaEnabledAt(OffsetDateTime.now()).build();
    }
}
