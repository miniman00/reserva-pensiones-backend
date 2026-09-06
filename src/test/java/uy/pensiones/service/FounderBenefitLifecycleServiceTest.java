package uy.pensiones.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import uy.pensiones.config.AppProperties;
import uy.pensiones.enums.LaunchCampaignBeneficiaryStatus;
import uy.pensiones.enums.NotificationType;
import uy.pensiones.mail.MailService;
import uy.pensiones.model.LaunchCampaign;
import uy.pensiones.model.LaunchCampaignBeneficiary;
import uy.pensiones.model.User;
import uy.pensiones.repo.LaunchCampaignBeneficiaryRepository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FounderBenefitLifecycleServiceTest {

    @Test
    void thirtyDayReminderCreatesPortalNotificationAndQueuesEmailOnlyOnce() {
        LaunchCampaignBeneficiaryRepository beneficiaries = mock(LaunchCampaignBeneficiaryRepository.class);
        NotificationService notifications = mock(NotificationService.class);
        MailService mail = mock(MailService.class);
        AppProperties app = new AppProperties();
        app.setFrontendUrl("https://pensiones.example/");
        FounderBenefitLifecycleService service = new FounderBenefitLifecycleService(
                beneficiaries, notifications, mail, app, "America/Montevideo");

        OffsetDateTime now = OffsetDateTime.of(2026, 9, 3, 12, 0, 0, 0, ZoneOffset.UTC);
        LaunchCampaignBeneficiary beneficiary = beneficiary(now.plusDays(30));
        when(beneficiaries.findLifecycleCandidates(anyString(), eq(LaunchCampaignBeneficiaryStatus.ACTIVE), any()))
                .thenReturn(List.of(beneficiary));
        when(notifications.createOnce(eq(beneficiary.getUser()), eq(NotificationType.FOUNDER_BENEFIT_EXPIRING),
                anyString(), anyString(), eq("/profile/commercial"), anyString()))
                .thenReturn(true, false);

        service.process(now);
        service.process(now.plusMinutes(20));

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(notifications, times(2)).createOnce(eq(beneficiary.getUser()),
                eq(NotificationType.FOUNDER_BENEFIT_EXPIRING), anyString(), anyString(),
                eq("/profile/commercial"), key.capture());
        assertThat(key.getAllValues()).allMatch(value -> value.contains(":reminder-30:"));
        verify(mail, times(1)).sendFounderBenefitNotice(
                eq("owner@example.com"), eq("Propietario"), contains("30 días"), anyString(),
                eq("https://pensiones.example/profile/commercial"));
    }

    @Test
    void expirationNoticeWaitsUntilExactExpirationAndThenIsIdempotent() {
        LaunchCampaignBeneficiaryRepository beneficiaries = mock(LaunchCampaignBeneficiaryRepository.class);
        NotificationService notifications = mock(NotificationService.class);
        MailService mail = mock(MailService.class);
        AppProperties app = new AppProperties();
        FounderBenefitLifecycleService service = new FounderBenefitLifecycleService(
                beneficiaries, notifications, mail, app, "America/Montevideo");

        OffsetDateTime now = OffsetDateTime.of(2026, 9, 3, 12, 0, 0, 0, ZoneOffset.UTC);
        LaunchCampaignBeneficiary beneficiary = beneficiary(now.plusMinutes(30));
        when(beneficiaries.findLifecycleCandidates(anyString(), eq(LaunchCampaignBeneficiaryStatus.ACTIVE), any()))
                .thenReturn(List.of(beneficiary));
        when(notifications.createOnce(eq(beneficiary.getUser()), eq(NotificationType.FOUNDER_BENEFIT_EXPIRED),
                anyString(), anyString(), eq("/profile/commercial"), anyString()))
                .thenReturn(true, false);

        service.process(now);
        verify(notifications, never()).createOnce(eq(beneficiary.getUser()),
                eq(NotificationType.FOUNDER_BENEFIT_EXPIRED), anyString(), anyString(), anyString(), anyString());

        service.process(now.plusHours(1));
        service.process(now.plusHours(2));

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(notifications, times(2)).createOnce(eq(beneficiary.getUser()),
                eq(NotificationType.FOUNDER_BENEFIT_EXPIRED), anyString(), anyString(),
                eq("/profile/commercial"), key.capture());
        assertThat(key.getAllValues()).allMatch(value -> value.contains(":expired:"));
        verify(mail, times(1)).sendFounderBenefitNotice(eq("owner@example.com"), eq("Propietario"),
                contains("finalizó"), anyString(), eq("http://localhost:5173/profile/commercial"));
    }

    @Test
    void extensionGetsANewDedupCycleBecauseExpirationIsPartOfTheKey() {
        LaunchCampaignBeneficiaryRepository beneficiaries = mock(LaunchCampaignBeneficiaryRepository.class);
        NotificationService notifications = mock(NotificationService.class);
        MailService mail = mock(MailService.class);
        AppProperties app = new AppProperties();
        FounderBenefitLifecycleService service = new FounderBenefitLifecycleService(
                beneficiaries, notifications, mail, app, "America/Montevideo");

        OffsetDateTime now = OffsetDateTime.of(2026, 9, 3, 12, 0, 0, 0, ZoneOffset.UTC);
        LaunchCampaignBeneficiary beneficiary = beneficiary(now.plusDays(7));
        when(beneficiaries.findLifecycleCandidates(anyString(), eq(LaunchCampaignBeneficiaryStatus.ACTIVE), any()))
                .thenReturn(List.of(beneficiary));
        when(notifications.createOnce(any(), any(), anyString(), anyString(), anyString(), anyString())).thenReturn(true);

        service.process(now);
        beneficiary.setExpiresAt(now.plusDays(14));
        service.process(now);

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(notifications, times(2)).createOnce(eq(beneficiary.getUser()),
                eq(NotificationType.FOUNDER_BENEFIT_EXPIRING), anyString(), anyString(),
                eq("/profile/commercial"), key.capture());
        assertThat(key.getAllValues().get(0)).contains(":reminder-7:");
        assertThat(key.getAllValues().get(1)).contains(":reminder-14:");
        assertThat(key.getAllValues().get(0)).isNotEqualTo(key.getAllValues().get(1));
    }

    @Test
    void suspendedOwnerDoesNotReceiveLifecycleNotices() {
        LaunchCampaignBeneficiaryRepository beneficiaries = mock(LaunchCampaignBeneficiaryRepository.class);
        NotificationService notifications = mock(NotificationService.class);
        MailService mail = mock(MailService.class);
        AppProperties app = new AppProperties();
        FounderBenefitLifecycleService service = new FounderBenefitLifecycleService(
                beneficiaries, notifications, mail, app, "America/Montevideo");

        OffsetDateTime now = OffsetDateTime.of(2026, 9, 3, 12, 0, 0, 0, ZoneOffset.UTC);
        LaunchCampaignBeneficiary beneficiary = beneficiary(now.plusDays(7));
        beneficiary.getUser().setSuspended(true);
        when(beneficiaries.findLifecycleCandidates(anyString(), eq(LaunchCampaignBeneficiaryStatus.ACTIVE), any()))
                .thenReturn(List.of(beneficiary));

        service.process(now);

        verifyNoInteractions(notifications, mail);
    }

    private LaunchCampaignBeneficiary beneficiary(OffsetDateTime expiresAt) {
        User user = User.builder().id(10L).name("Propietario").email("owner@example.com").build();
        LaunchCampaign campaign = LaunchCampaign.builder().id(1L)
                .code(FounderLaunchCampaignService.FOUNDER_CAMPAIGN_CODE)
                .name("Propietarios Fundadores").build();
        return LaunchCampaignBeneficiary.builder()
                .id(70L).campaign(campaign).user(user).grantedOrder(7)
                .grantedAt(expiresAt.minusDays(365)).expiresAt(expiresAt)
                .status(LaunchCampaignBeneficiaryStatus.ACTIVE).build();
    }
}
