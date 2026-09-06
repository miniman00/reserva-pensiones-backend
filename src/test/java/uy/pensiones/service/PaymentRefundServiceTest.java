package uy.pensiones.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uy.pensiones.enums.*;
import uy.pensiones.model.*;
import uy.pensiones.payment.PaymentRuntimeConfigurationService;
import uy.pensiones.repo.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PaymentRefundServiceTest {
    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final PaymentRefundRepository refunds = mock(PaymentRefundRepository.class);
    private final OwnerSubscriptionRepository subscriptions = mock(OwnerSubscriptionRepository.class);
    private final PensionPromotionRepository promotions = mock(PensionPromotionRepository.class);
    private final PaymentTransactionService transactions = mock(PaymentTransactionService.class);
    private final PaymentRuntimeConfigurationService runtime = mock(PaymentRuntimeConfigurationService.class);
    private final AdminAuditService audit = mock(AdminAuditService.class);
    private PaymentRefundService service;
    private BackofficeUser actor;

    @BeforeEach
    void setUp() {
        PaymentBenefitReconciliationService benefitReconciliation =
                new PaymentBenefitReconciliationService(subscriptions, promotions, audit);
        service = new PaymentRefundService(payments, refunds, transactions, benefitReconciliation, runtime, audit);
        actor = BackofficeUser.builder().id(7L).username("admin").displayName("Admin").role(BackofficeRole.ADMIN).build();
    }

    @Test
    void keepingBenefitAfterRefundClearsPendingReviewWithoutCancellingSubscription() {
        OwnerSubscription subscription = OwnerSubscription.builder().id(10L).status(SubscriptionStatus.ACTIVE).build();
        PaymentRecord payment = refundedPayment(subscription);
        when(payments.findByIdForUpdate(1L)).thenReturn(Optional.of(payment));
        when(audit.requireReason("Excepción comercial")).thenReturn("Excepción comercial");
        when(payments.save(payment)).thenReturn(payment);

        service.reconcileBenefit(1L, RefundBenefitDecision.KEEP_BENEFIT, "Excepción comercial", actor);

        assertThat(payment.getRefundBenefitDecision()).isEqualTo(RefundBenefitDecision.KEEP_BENEFIT);
        assertThat(payment.getFulfillmentErrorCode()).isNull();
        assertThat(subscription.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        verify(subscriptions, never()).save(any());
    }

    @Test
    void revokingBenefitAfterRefundCancelsPaidSubscriptionExplicitly() {
        OwnerSubscription subscription = OwnerSubscription.builder().id(10L).status(SubscriptionStatus.ACTIVE).build();
        PaymentRecord payment = refundedPayment(subscription);
        when(payments.findByIdForUpdate(1L)).thenReturn(Optional.of(payment));
        when(audit.requireReason("Devolución total confirmada")).thenReturn("Devolución total confirmada");
        when(payments.save(payment)).thenReturn(payment);
        when(subscriptions.save(subscription)).thenReturn(subscription);

        service.reconcileBenefit(1L, RefundBenefitDecision.REVOKE_BENEFIT, "Devolución total confirmada", actor);

        assertThat(subscription.getStatus()).isEqualTo(SubscriptionStatus.CANCELLED);
        assertThat(subscription.getCancellationReason()).contains("Reembolso del pago #1");
        assertThat(payment.getRefundBenefitDecision()).isEqualTo(RefundBenefitDecision.REVOKE_BENEFIT);
        verify(subscriptions).save(subscription);
        verify(audit).record(eq(actor), eq(AdminAuditAction.ADMIN_RECONCILE_REFUND_BENEFIT),
                eq(AdminAuditEntityType.SUBSCRIPTION), eq(10L), any(), any(), eq("Devolución total confirmada"));
    }

    @Test
    void refundableAmountUsesHighestKnownProviderOrAcceptedRefundAmount() {
        PaymentRecord payment = PaymentRecord.builder().id(1L).amount(new BigDecimal("1000.00"))
                .providerRefundedAmount(new BigDecimal("200.00")).status(PaymentStatus.APPROVED).build();
        when(refunds.sumAmountByPaymentAndStatus(1L, PaymentRefundStatus.ACCEPTED)).thenReturn(new BigDecimal("350.00"));

        assertThat(service.refundableAmount(payment)).isEqualByComparingTo("650.00");
    }

    private PaymentRecord refundedPayment(OwnerSubscription subscription) {
        return PaymentRecord.builder().id(1L).amount(new BigDecimal("990.00")).currency("UYU")
                .status(PaymentStatus.REFUNDED).fulfilledAt(OffsetDateTime.now().minusDays(1))
                .fulfilledSubscription(subscription).fulfillmentErrorCode("REFUND_REQUIRES_BENEFIT_REVIEW")
                .fulfillmentErrorMessage("Revisar").providerRefundedAmount(new BigDecimal("990.00")).build();
    }
}
