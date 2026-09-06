package uy.pensiones.service;

import org.junit.jupiter.api.Test;
import uy.pensiones.enums.AdminAuditAction;
import uy.pensiones.enums.AdminAuditEntityType;
import uy.pensiones.enums.PensionStatus;
import uy.pensiones.model.BackofficeUser;
import uy.pensiones.model.Pension;
import uy.pensiones.model.User;
import uy.pensiones.repo.AdminPensionQueryRepository;
import uy.pensiones.repo.PensionRepository;
import uy.pensiones.repo.PensionMediaRepository;
import uy.pensiones.repo.PensionMemberRepository;
import uy.pensiones.repo.PensionReportRepository;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AdminPensionServiceTest {

    private final AdminPensionQueryRepository queryRepository = mock(AdminPensionQueryRepository.class);
    private final PensionRepository pensionRepository = mock(PensionRepository.class);
    private final PensionMediaRepository mediaRepository = mock(PensionMediaRepository.class);
    private final PensionMemberRepository memberRepository = mock(PensionMemberRepository.class);
    private final PensionReportRepository reportRepository = mock(PensionReportRepository.class);
    private final AdminAuditService audit = mock(AdminAuditService.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final PensionMediaDtoMapper mediaMapper = mock(PensionMediaDtoMapper.class);
    private final AdminPensionService service = new AdminPensionService(
            queryRepository, pensionRepository, mediaRepository, memberRepository, reportRepository,
            audit, notifications, mediaMapper
    );

    @Test
    void blockingPublishedPensionPausesItAndWritesAudit() {
        BackofficeUser actor = BackofficeUser.builder().id(7L).username("moderador").displayName("Moderador").build();
        User owner = User.builder().id(22L).email("owner@example.com").name("Owner").build();
        Pension pension = Pension.builder()
                .id(42L)
                .name("Pensión Centro")
                .owner(owner)
                .createdBy(owner)
                .status(PensionStatus.PUBLISHED)
                .moderationBlocked(false)
                .build();
        when(audit.requireReason(" Información inconsistente ")).thenReturn("Información inconsistente");
        when(pensionRepository.findWithOwnerById(42L)).thenReturn(Optional.of(pension));

        var result = service.block(42L, " Información inconsistente ", actor);

        assertThat(result.status()).isEqualTo(PensionStatus.PAUSED);
        assertThat(result.moderationBlocked()).isTrue();
        verify(pensionRepository).save(pension);
        verify(audit).record(eq(actor), eq(AdminAuditAction.ADMIN_BLOCK_PENSION),
                eq(AdminAuditEntityType.PENSION), eq(42L), any(), any(), eq("Información inconsistente"));
    }

    @Test
    void unblockingDoesNotRepublishAutomatically() {
        BackofficeUser actor = BackofficeUser.builder().id(7L).username("moderador").displayName("Moderador").build();
        User owner = User.builder().id(22L).email("owner@example.com").name("Owner").build();
        Pension pension = Pension.builder()
                .id(42L)
                .name("Pensión Centro")
                .owner(owner)
                .createdBy(owner)
                .status(PensionStatus.PAUSED)
                .moderationBlocked(true)
                .build();
        when(audit.requireReason("Revisión completada")).thenReturn("Revisión completada");
        when(pensionRepository.findWithOwnerById(42L)).thenReturn(Optional.of(pension));

        var result = service.unblock(42L, "Revisión completada", actor);

        assertThat(result.status()).isEqualTo(PensionStatus.PAUSED);
        assertThat(result.moderationBlocked()).isFalse();
        verify(audit).record(eq(actor), eq(AdminAuditAction.ADMIN_UNBLOCK_PENSION),
                eq(AdminAuditEntityType.PENSION), eq(42L), any(), any(), eq("Revisión completada"));
    }

    @Test
    void blockingAlreadyBlockedPensionIsRejectedWithoutAuditRecord() {
        BackofficeUser actor = BackofficeUser.builder().id(7L).username("moderador").displayName("Moderador").build();
        User owner = User.builder().id(22L).email("owner@example.com").build();
        Pension pension = Pension.builder()
                .id(42L)
                .name("Pensión Centro")
                .owner(owner)
                .createdBy(owner)
                .status(PensionStatus.PAUSED)
                .moderationBlocked(true)
                .build();
        when(audit.requireReason("Duplicado")).thenReturn("Duplicado");
        when(pensionRepository.findWithOwnerById(42L)).thenReturn(Optional.of(pension));

        assertThatThrownBy(() -> service.block(42L, "Duplicado", actor))
                .hasMessageContaining("ya está bloqueada");

        verify(audit, never()).record(any(), any(), any(), any(), any(), any(), any());
    }
}
