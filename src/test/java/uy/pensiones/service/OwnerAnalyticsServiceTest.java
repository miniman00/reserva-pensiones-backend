package uy.pensiones.service;

import org.junit.jupiter.api.Test;
import uy.pensiones.model.Pension;
import uy.pensiones.repo.OwnerAnalyticsQueryRepository;
import uy.pensiones.security.Authz;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OwnerAnalyticsServiceTest {
    @Test
    void advancedAnalyticsBuildsCompleteDailySeriesForOwnedPension() {
        OwnerEntitlementService entitlements = mock(OwnerEntitlementService.class);
        PensionService pensions = mock(PensionService.class);
        Authz authz = mock(Authz.class);
        OwnerAnalyticsQueryRepository query = mock(OwnerAnalyticsQueryRepository.class);
        OwnerAnalyticsService service = new OwnerAnalyticsService(entitlements, pensions, authz, query);
        Pension pension = Pension.builder().id(7L).name("Centro").city("Montevideo").build();
        when(pensions.myPensions(10L)).thenReturn(List.of(pension));
        when(authz.isOwner(10L, 7L)).thenReturn(true);
        when(query.views(anyCollection(), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.<Object[]>of(new Object[]{7L, LocalDate.now(), 4L}));
        when(query.favorites(anyCollection(), any(LocalDate.class), any(LocalDate.class))).thenReturn(List.of());
        when(query.inquiries(anyCollection(), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.<Object[]>of(new Object[]{7L, LocalDate.now(), 2L, 1L}));

        var result = service.pension(10L, 7L, 7);

        verify(entitlements).requireAdvancedAnalytics(10L);
        assertEquals(7, result.daily().size());
        assertEquals(4, result.summary().views());
        assertEquals(2, result.summary().inquiries());
        assertEquals(1, result.summary().conversions());
        assertEquals(50d, result.summary().inquiryRate());
        assertEquals(50d, result.summary().conversionRate());
    }

    @Test
    void analyticsDoesNotExposeCollaboratorPensionAsOwned() {
        OwnerEntitlementService entitlements = mock(OwnerEntitlementService.class);
        PensionService pensions = mock(PensionService.class);
        Authz authz = mock(Authz.class);
        OwnerAnalyticsQueryRepository query = mock(OwnerAnalyticsQueryRepository.class);
        OwnerAnalyticsService service = new OwnerAnalyticsService(entitlements, pensions, authz, query);
        Pension pension = Pension.builder().id(7L).name("Ajena").build();
        when(pensions.myPensions(10L)).thenReturn(List.of(pension));
        when(authz.isOwner(10L, 7L)).thenReturn(false);

        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> service.pension(10L, 7L, 30));
        verifyNoInteractions(query);
    }
}
