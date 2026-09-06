package uy.pensiones.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import uy.pensiones.enums.PaymentPurpose;
import uy.pensiones.enums.PaymentStatus;
import uy.pensiones.enums.PromotionTargetType;
import uy.pensiones.model.OwnerSubscription;
import uy.pensiones.model.PaymentRecord;
import uy.pensiones.model.Pension;
import uy.pensiones.model.Plan;
import uy.pensiones.model.PlanVersion;
import uy.pensiones.model.PromotionProduct;
import uy.pensiones.model.PromotionProductVersion;
import uy.pensiones.repo.PaymentRepository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OwnerPaymentQueryServiceTest {

    private PaymentRepository payments;
    private OwnerPaymentQueryService service;

    @BeforeEach
    void setUp() {
        payments = mock(PaymentRepository.class);
        service = new OwnerPaymentQueryService(payments);
    }

    @Test
    void pendingPaymentExposesOnlyResumableCheckoutData() {
        PaymentRecord payment = PaymentRecord.builder()
                .id(10L)
                .purpose(PaymentPurpose.SUBSCRIPTION)
                .status(PaymentStatus.PENDING)
                .amount(new BigDecimal("1290.00"))
                .currency("UYU")
                .checkoutUrl("https://checkout.example/10")
                .planVersion(PlanVersion.builder()
                        .id(20L)
                        .version(3)
                        .plan(Plan.builder().code("PRO").name("Profesional").build())
                        .build())
                .subscriptionPeriodMonths(1)
                .build();
        when(payments.findOwnerDetailedById(10L, 7L)).thenReturn(Optional.of(payment));

        var result = service.detail(7L, 10L);

        assertEquals(PaymentStatus.PENDING, result.status());
        assertEquals("https://checkout.example/10", result.checkoutUrl());
        assertTrue(result.canResumeCheckout());
        assertFalse(result.canRetry());
        assertFalse(result.terminal());
        assertEquals("PRO", result.purchase().planCode());
        assertNull(result.purchase().pensionId());
    }

    @Test
    void approvedPaymentHidesStaleCheckoutAndReportsFulfillment() {
        OffsetDateTime fulfilledAt = OffsetDateTime.parse("2026-09-01T19:30:00-03:00");
        PaymentRecord payment = PaymentRecord.builder()
                .id(11L)
                .purpose(PaymentPurpose.SUBSCRIPTION)
                .status(PaymentStatus.APPROVED)
                .amount(new BigDecimal("1290.00"))
                .currency("UYU")
                .checkoutUrl("https://checkout.example/11")
                .fulfilledAt(fulfilledAt)
                .fulfilledSubscription(OwnerSubscription.builder().id(70L).build())
                .build();
        when(payments.findOwnerDetailedById(11L, 7L)).thenReturn(Optional.of(payment));

        var result = service.detail(7L, 11L);

        assertNull(result.checkoutUrl());
        assertFalse(result.canResumeCheckout());
        assertFalse(result.canRetry());
        assertTrue(result.terminal());
        assertTrue(result.benefitApplied());
        assertFalse(result.fulfillmentPending());
        assertEquals(fulfilledAt, result.fulfilledAt());
        assertFalse(result.statusMessage().toLowerCase().contains("provider"));
    }

    @Test
    void approvedWithoutFulfillmentIsReportedAsActivationPendingWithoutInternalErrorDetails() {
        PaymentRecord payment = PaymentRecord.builder()
                .id(12L)
                .purpose(PaymentPurpose.PROMOTION)
                .status(PaymentStatus.APPROVED)
                .amount(new BigDecimal("390.00"))
                .currency("UYU")
                .fulfillmentErrorCode("INTERNAL_CODE")
                .fulfillmentErrorMessage("sensitive internal detail")
                .pension(Pension.builder().id(90L).name("Pensión Centro").build())
                .promotionProductVersion(PromotionProductVersion.builder()
                        .id(44L)
                        .product(PromotionProduct.builder().code("FEATURED").name("Destacado")
                                .targetType(PromotionTargetType.GLOBAL).build())
                        .build())
                .build();
        when(payments.findOwnerDetailedById(12L, 7L)).thenReturn(Optional.of(payment));

        var result = service.detail(7L, 12L);

        assertTrue(result.fulfillmentPending());
        assertFalse(result.benefitApplied());
        assertTrue(result.statusMessage().contains("pendiente de activación"));
        assertFalse(result.toString().contains("INTERNAL_CODE"));
        assertFalse(result.toString().contains("sensitive internal detail"));
        assertEquals(90L, result.purchase().pensionId());
        assertEquals("FEATURED", result.purchase().promotionProductCode());
    }

    @Test
    void failedPaymentCanBeRetriedButNeverReturnsOldCheckoutUrl() {
        PaymentRecord payment = PaymentRecord.builder()
                .id(13L)
                .purpose(PaymentPurpose.PROMOTION)
                .status(PaymentStatus.REJECTED)
                .amount(BigDecimal.TEN)
                .currency("UYU")
                .checkoutUrl("https://checkout.example/13")
                .build();
        when(payments.findOwnerDetailedById(13L, 7L)).thenReturn(Optional.of(payment));

        var result = service.detail(7L, 13L);

        assertTrue(result.canRetry());
        assertNull(result.checkoutUrl());
        assertFalse(result.canResumeCheckout());
    }

    @Test
    void paymentOwnedByAnotherUserLooksNotFound() {
        when(payments.findOwnerDetailedById(99L, 7L)).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.detail(7L, 99L));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        assertEquals("Pago no encontrado", ex.getReason());
    }

    @Test
    void listIsRestrictedToAuthenticatedUserAndClampsPageSize() {
        PaymentRecord payment = PaymentRecord.builder()
                .id(20L)
                .purpose(PaymentPurpose.SUBSCRIPTION)
                .status(PaymentStatus.EXPIRED)
                .amount(BigDecimal.ONE)
                .currency("UYU")
                .build();
        when(payments.findOwnerPayments(eq(7L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(payment)));

        var result = service.list(7L, -3, 500);

        assertEquals(1, result.getTotalElements());
        assertTrue(result.getContent().get(0).canRetry());
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(payments).findOwnerPayments(eq(7L), pageable.capture());
        assertEquals(0, pageable.getValue().getPageNumber());
        assertEquals(50, pageable.getValue().getPageSize());
    }
}
