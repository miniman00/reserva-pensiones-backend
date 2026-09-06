package uy.pensiones.service;

import org.junit.jupiter.api.Test;
import uy.pensiones.config.AppProperties;
import uy.pensiones.config.CatalogQualityProperties;
import uy.pensiones.enums.NotificationType;
import uy.pensiones.enums.PensionStatus;
import uy.pensiones.mail.MailService;
import uy.pensiones.model.Pension;
import uy.pensiones.model.User;
import uy.pensiones.repo.PensionRepository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PensionCatalogMaintenanceServiceTest {

    @Test
    void stalePublishedPensionGetsHiddenReminderOnlyOncePerAvailabilityCycle() {
        PensionRepository pensions = mock(PensionRepository.class);
        NotificationService notifications = mock(NotificationService.class);
        MailService mail = mock(MailService.class);
        CatalogQualityProperties quality = new CatalogQualityProperties();
        AppProperties app = new AppProperties();
        app.setFrontendUrl("https://pensiones.example");

        OffsetDateTime now = OffsetDateTime.of(2026, 9, 3, 12, 0, 0, 0, ZoneOffset.UTC);
        User owner = User.builder().id(7L).email("owner@example.com").build();
        Pension pension = Pension.builder()
                .id(42L)
                .name("Pensión Centro")
                .owner(owner)
                .status(PensionStatus.PUBLISHED)
                .availabilityUpdatedAt(now.minusDays(31))
                .build();

        when(pensions.findAvailabilityMaintenanceCandidates(eq(PensionStatus.PUBLISHED), any()))
                .thenReturn(List.of(pension));
        when(notifications.createOnce(eq(owner), eq(NotificationType.PENSION_AVAILABILITY_HIDDEN),
                anyString(), anyString(), eq("/profile/pensions/42/edit?step=pricing"), anyString()))
                .thenReturn(true, false);

        PensionCatalogMaintenanceService service = new PensionCatalogMaintenanceService(
                pensions, notifications, mail, app, quality);

        service.remindPublishedAvailability(now);
        service.remindPublishedAvailability(now.plusHours(1));

        verify(notifications, times(2)).createOnce(
                eq(owner), eq(NotificationType.PENSION_AVAILABILITY_HIDDEN), anyString(), anyString(),
                eq("/profile/pensions/42/edit?step=pricing"), anyString());
        verify(mail, times(1)).sendPensionMaintenanceReminder(
                eq("owner@example.com"), eq("Pensión Centro"), anyString(), anyString(),
                eq("https://pensiones.example/profile/pensions/42/edit?step=pricing"));
    }

    @Test
    void inactiveDraftGetsReminderWithoutDeletingOrChangingIt() {
        PensionRepository pensions = mock(PensionRepository.class);
        NotificationService notifications = mock(NotificationService.class);
        MailService mail = mock(MailService.class);
        CatalogQualityProperties quality = new CatalogQualityProperties();
        AppProperties app = new AppProperties();

        OffsetDateTime now = OffsetDateTime.of(2026, 9, 3, 12, 0, 0, 0, ZoneOffset.UTC);
        User owner = User.builder().id(9L).email("draft@example.com").build();
        Pension draft = Pension.builder()
                .id(88L)
                .name("Borrador Cordón")
                .owner(owner)
                .status(PensionStatus.DRAFT)
                .draftStep("media")
                .updatedAt(now.minusDays(8))
                .build();

        when(pensions.findDraftMaintenanceCandidates(eq(PensionStatus.DRAFT), any()))
                .thenReturn(List.of(draft));
        when(notifications.createOnce(eq(owner), eq(NotificationType.PENSION_DRAFT_REMINDER),
                anyString(), anyString(), eq("/profile/pensions/88/edit?step=media"), anyString()))
                .thenReturn(true);

        PensionCatalogMaintenanceService service = new PensionCatalogMaintenanceService(
                pensions, notifications, mail, app, quality);
        service.remindAbandonedDrafts(now);

        verify(notifications).createOnce(
                eq(owner), eq(NotificationType.PENSION_DRAFT_REMINDER), anyString(), anyString(),
                eq("/profile/pensions/88/edit?step=media"), anyString());
        verify(pensions, never()).delete(any(Pension.class));
        verify(pensions, never()).save(any(Pension.class));
    }
}
