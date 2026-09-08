package uy.pensiones.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import uy.pensiones.config.AppProperties;
import uy.pensiones.model.BackofficeUser;
import uy.pensiones.model.Plan;
import uy.pensiones.model.PlanVersion;
import uy.pensiones.payment.PaymentRuntimeConfigurationService;
import uy.pensiones.repo.PlanRepository;
import uy.pensiones.repo.PlanVersionRepository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AdminCommercialPlanServicePeriodPricingTest {

    @Test
    void draftPersistsExplicitPricesByPeriodAndKeepsMonthlyReference() {
        PlanRepository plans = mock(PlanRepository.class);
        PlanVersionRepository versions = mock(PlanVersionRepository.class);
        AdminAuditService audit = mock(AdminAuditService.class);
        AdminCommercialPlanService service = new AdminCommercialPlanService(
                plans, versions, audit, new AppProperties(), mock(PaymentRuntimeConfigurationService.class));

        Plan plan = Plan.builder().id(2L).code("PRO").name("Pro").active(true).build();
        when(plans.findByIdForUpdate(2L)).thenReturn(Optional.of(plan));
        when(versions.findTopByPlanIdOrderByVersionDesc(2L)).thenReturn(Optional.empty());
        when(versions.save(any(PlanVersion.class))).thenAnswer(invocation -> {
            PlanVersion version = invocation.getArgument(0);
            version.setId(22L);
            return version;
        });

        var input = new AdminCommercialPlanService.VersionInput(
                new BigDecimal("590.00"),
                List.of(
                        new AdminCommercialPlanService.PeriodPriceInput(1, new BigDecimal("590.00"), true),
                        new AdminCommercialPlanService.PeriodPriceInput(3, new BigDecimal("1590.00"), true),
                        new AdminCommercialPlanService.PeriodPriceInput(6, new BigDecimal("2890.00"), true),
                        new AdminCommercialPlanService.PeriodPriceInput(12, new BigDecimal("4990.00"), true)
                ),
                "UYU", 3, 3, 20, 2, 10,
                true, true, true, true,
                OffsetDateTime.now().plusMinutes(5), null
        );

        var result = service.createDraft(2L, input, BackofficeUser.builder().id(9L).username("soporte").build());

        assertEquals(new BigDecimal("590.00"), result.monthlyPrice());
        assertEquals(4, result.periodPrices().size());
        assertEquals(new BigDecimal("1590.00"), result.periodPrices().get(1).totalPrice());
        assertEquals(12, result.periodPrices().get(3).periodMonths());

        var captured = org.mockito.ArgumentCaptor.forClass(PlanVersion.class);
        verify(versions).save(captured.capture());
        assertEquals(4, captured.getValue().getPeriodPrices().size());
        assertSame(captured.getValue(), captured.getValue().getPeriodPrices().get(0).getPlanVersion());
    }

    @Test
    void legacyPayloadWithoutPeriodPricesIsBackfilledUsingHistoricalMultiplication() {
        PlanRepository plans = mock(PlanRepository.class);
        PlanVersionRepository versions = mock(PlanVersionRepository.class);
        AdminCommercialPlanService service = new AdminCommercialPlanService(
                plans, versions, mock(AdminAuditService.class), new AppProperties(), mock(PaymentRuntimeConfigurationService.class));

        Plan plan = Plan.builder().id(2L).code("PRO").name("Pro").active(true).build();
        when(plans.findByIdForUpdate(2L)).thenReturn(Optional.of(plan));
        when(versions.findTopByPlanIdOrderByVersionDesc(2L)).thenReturn(Optional.empty());
        when(versions.save(any(PlanVersion.class))).thenAnswer(invocation -> {
            PlanVersion version = invocation.getArgument(0);
            version.setId(22L);
            return version;
        });

        var input = new AdminCommercialPlanService.VersionInput(
                new BigDecimal("590.00"), null, "UYU",
                null, null, null, null, 0,
                false, false, false, false,
                OffsetDateTime.now().plusMinutes(5), null
        );

        var result = service.createDraft(2L, input, BackofficeUser.builder().id(9L).username("soporte").build());

        assertEquals(new BigDecimal("1770.00"), result.periodPrices().get(1).totalPrice());
        assertEquals(new BigDecimal("7080.00"), result.periodPrices().get(3).totalPrice());
    }

    @Test
    void rejectsUnsupportedOrDuplicatedPeriods() {
        PlanRepository plans = mock(PlanRepository.class);
        PlanVersionRepository versions = mock(PlanVersionRepository.class);
        AdminCommercialPlanService service = new AdminCommercialPlanService(
                plans, versions, mock(AdminAuditService.class), new AppProperties(), mock(PaymentRuntimeConfigurationService.class));
        Plan plan = Plan.builder().id(2L).code("PRO").name("Pro").active(true).build();
        when(plans.findByIdForUpdate(2L)).thenReturn(Optional.of(plan));

        var unsupported = new AdminCommercialPlanService.VersionInput(
                new BigDecimal("590.00"),
                List.of(new AdminCommercialPlanService.PeriodPriceInput(2, new BigDecimal("1000.00"), true)),
                "UYU", null, null, null, null, 0,
                false, false, false, false, OffsetDateTime.now().plusMinutes(5), null);

        ResponseStatusException unsupportedEx = assertThrows(ResponseStatusException.class,
                () -> service.createDraft(2L, unsupported, BackofficeUser.builder().id(9L).build()));
        assertEquals(HttpStatus.BAD_REQUEST, unsupportedEx.getStatusCode());

        var duplicate = new AdminCommercialPlanService.VersionInput(
                new BigDecimal("590.00"),
                List.of(
                        new AdminCommercialPlanService.PeriodPriceInput(3, new BigDecimal("1590.00"), true),
                        new AdminCommercialPlanService.PeriodPriceInput(3, new BigDecimal("1500.00"), true)),
                "UYU", null, null, null, null, 0,
                false, false, false, false, OffsetDateTime.now().plusMinutes(5), null);

        ResponseStatusException duplicateEx = assertThrows(ResponseStatusException.class,
                () -> service.createDraft(2L, duplicate, BackofficeUser.builder().id(9L).build()));
        assertEquals(HttpStatus.BAD_REQUEST, duplicateEx.getStatusCode());
        assertTrue(duplicateEx.getReason().contains("repetido"));
    }
}
