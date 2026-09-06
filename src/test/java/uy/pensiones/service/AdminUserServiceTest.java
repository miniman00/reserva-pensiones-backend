package uy.pensiones.service;

import org.junit.jupiter.api.Test;
import uy.pensiones.enums.AdminAuditAction;
import uy.pensiones.enums.AdminAuditEntityType;
import uy.pensiones.enums.BackofficeRole;
import uy.pensiones.enums.UserRole;
import uy.pensiones.model.BackofficeUser;
import uy.pensiones.model.User;
import uy.pensiones.repo.AdminUserQueryRepository;
import uy.pensiones.repo.UserRepository;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AdminUserServiceTest {

    private final UserRepository users = mock(UserRepository.class);
    private final AdminUserQueryRepository adminUsers = mock(AdminUserQueryRepository.class);
    private final AdminAuditService audit = mock(AdminAuditService.class);
    private final AdminUserService service = new AdminUserService(users, adminUsers, audit);
    private final BackofficeUser actor = BackofficeUser.builder()
            .id(9L).username("admin").displayName("Administrador").role(BackofficeRole.ADMIN).build();

    @Test
    void suspensionRequiresReasonAndAuditsBeforeAfter() {
        User target = User.builder().id(30L).email("owner@example.com").role(UserRole.OWNER).build();
        when(users.findById(30L)).thenReturn(Optional.of(target));
        when(audit.requireReason("Incumplimiento confirmado")).thenReturn("Incumplimiento confirmado");
        when(users.save(target)).thenReturn(target);

        var result = service.suspend(30L, "Incumplimiento confirmado", actor);

        assertThat(result.suspended()).isTrue();
        assertThat(result.suspensionReason()).isEqualTo("Incumplimiento confirmado");
        verify(audit).record(eq(actor), eq(AdminAuditAction.ADMIN_SUSPEND_USER),
                eq(AdminAuditEntityType.MARKETPLACE_USER), eq(30L), any(), any(),
                eq("Incumplimiento confirmado"));
    }

    @Test
    void reactivationDoesNotEraseAuditReason() {
        User target = User.builder()
                .id(30L).email("owner@example.com").role(UserRole.OWNER)
                .suspended(true).suspensionReason("Motivo anterior").build();
        when(users.findById(30L)).thenReturn(Optional.of(target));
        when(audit.requireReason("Apelación aceptada")).thenReturn("Apelación aceptada");
        when(users.save(target)).thenReturn(target);

        var result = service.reactivate(30L, "Apelación aceptada", actor);

        assertThat(result.suspended()).isFalse();
        assertThat(result.suspensionReason()).isNull();
        verify(audit).record(eq(actor), eq(AdminAuditAction.ADMIN_REACTIVATE_USER),
                eq(AdminAuditEntityType.MARKETPLACE_USER), eq(30L), any(), any(),
                eq("Apelación aceptada"));
    }

    @Test
    void suspensionReasonLongerThanDatabaseFieldIsRejectedInsteadOfTruncated() {
        User target = User.builder().id(30L).email("owner@example.com").role(UserRole.OWNER).build();
        when(users.findById(30L)).thenReturn(Optional.of(target));
        String longReason = "x".repeat(501);
        when(audit.requireReason(longReason)).thenReturn(longReason);

        assertThatThrownBy(() -> service.suspend(30L, longReason, actor))
                .hasMessageContaining("500");

        verify(users, never()).save(any());
        verify(audit, never()).record(any(), any(), any(), any(), any(), any(), any());
    }
}
