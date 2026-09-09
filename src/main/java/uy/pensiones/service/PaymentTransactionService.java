package uy.pensiones.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import uy.pensiones.enums.*;
import uy.pensiones.model.*;
import uy.pensiones.payment.PaymentGateway;
import uy.pensiones.payment.PaymentRuntimeConfigurationService;
import uy.pensiones.repo.*;
import uy.pensiones.realtime.RealtimeEventService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class PaymentTransactionService {
    private final PaymentRepository payments;
    private final PaymentStatusHistoryRepository history;
    private final AdminSubscriptionUserRepository users;
    private final PlanRepository plans;
    private final PlanVersionRepository planVersions;
    private final PensionRepository pensions;
    private final PromotionProductRepository promotionProducts;
    private final PromotionProductVersionRepository promotionVersions;
    private final StudyCenterCatalogRepository studyCenters;
    private final OwnerSubscriptionRepository subscriptions;
    private final PensionPromotionRepository promotions;
    private final PaymentFulfillmentService fulfillment;
    private final PaymentRuntimeConfigurationService paymentRuntime;
    private final AdminAuditService audit;
    private final RealtimeEventService realtimeEvents;

    public PaymentTransactionService(PaymentRepository payments, PaymentStatusHistoryRepository history,
                                     AdminSubscriptionUserRepository users, PlanRepository plans,
                                     PlanVersionRepository planVersions, PensionRepository pensions,
                                     PromotionProductRepository promotionProducts,
                                     PromotionProductVersionRepository promotionVersions,
                                     StudyCenterCatalogRepository studyCenters,
                                     OwnerSubscriptionRepository subscriptions, PensionPromotionRepository promotions,
                                     PaymentFulfillmentService fulfillment, PaymentRuntimeConfigurationService paymentRuntime,
                                     AdminAuditService audit, RealtimeEventService realtimeEvents) {
        this.payments = payments;
        this.history = history;
        this.users = users;
        this.plans = plans;
        this.planVersions = planVersions;
        this.pensions = pensions;
        this.promotionProducts = promotionProducts;
        this.promotionVersions = promotionVersions;
        this.studyCenters = studyCenters;
        this.subscriptions = subscriptions;
        this.promotions = promotions;
        this.fulfillment = fulfillment;
        this.paymentRuntime = paymentRuntime;
        this.audit = audit;
        this.realtimeEvents = realtimeEvents;
    }

    @Transactional
    public PreparedPayment prepareMock(CreateInput input, BackofficeUser actor) {
        return prepare(PaymentProvider.MOCK, input, actor);
    }

    @Transactional
    public PreparedPayment prepare(PaymentProvider provider, CreateInput input, BackofficeUser actor) {
        requireBackofficeActor(actor);
        return prepareInternal(provider, input, actor, false, null, null);
    }

    @Transactional
    public PreparedPayment prepareMarketplace(PaymentProvider provider, CreateInput input, Long authenticatedUserId) {
        Long userId = requireId(authenticatedUserId);
        if (input == null || input.purpose() == null) throw bad("El propósito del pago es obligatorio");
        CreateInput normalized = new CreateInput(
                input.purpose(), userId, input.planVersionId(), input.subscriptionPeriodMonths(),
                input.pensionId(), input.promotionProductVersionId(), input.studyCenterId(), input.idempotencyKey());
        return prepareInternal(provider, normalized, null, false, null, userId);
    }

    @Transactional
    public PreparedPayment prepareCertificationLive(PaymentProvider provider, CreateInput input, BackofficeUser actor, String reason) {
        requireBackofficeActor(actor);
        return prepareInternal(provider, input, actor, true, audit.requireReason(reason), null);
    }

    private PreparedPayment prepareInternal(PaymentProvider provider, CreateInput input, BackofficeUser actor,
                                            boolean certificationLive, String certificationReason, Long marketplaceUserId) {
        if (provider == null) throw bad("El proveedor es obligatorio");
        if (input == null || input.purpose() == null) throw bad("El propósito del pago es obligatorio");
        if (!certificationLive) {
            if (marketplaceUserId != null) {
                paymentRuntime.requirePaymentsEnabledForMarketplace();
            } else {
                paymentRuntime.requirePaymentsEnabled();
                paymentRuntime.requireProviderEnabled(provider, input.purpose());
            }
        }
        PaymentProviderMode providerMode = paymentRuntime.provider(provider).getMode();
        String key = cleanKey(input.idempotencyKey());
        PaymentRecord existing = payments.findByIdempotencyKey(key).orElse(null);
        if (existing != null) {
            assertIdempotentMatch(existing, provider, providerMode, input);
            return new PreparedPayment(existing.getId(), gatewayRequest(existing), false);
        }

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        PaymentRecord payment = switch (input.purpose()) {
            case SUBSCRIPTION -> prepareSubscription(provider, providerMode, input, actor, key, now, marketplaceUserId);
            case PROMOTION -> preparePromotion(provider, providerMode, input, actor, key, now, marketplaceUserId);
        };
        if (certificationLive) {
            paymentRuntime.requireProviderScopeForCertification(provider, payment.getPurpose(), payment.getCurrency(), payment.getUser().getCountryCode());
        } else if (marketplaceUserId != null) {
            paymentRuntime.requireProviderScopeForMarketplace(provider, payment.getPurpose(), payment.getCurrency(), payment.getUser().getCountryCode());
        } else {
            paymentRuntime.requireProviderScope(provider, payment.getPurpose(), payment.getCurrency(), payment.getUser().getCountryCode());
        }
        payment = payments.save(payment);
        addHistory(payment, null, PaymentStatus.CREATED, null, PaymentEventSource.CREATE,
                certificationLive ? "Pago LIVE controlado creado para certificación del proveedor"
                        : marketplaceUserId != null ? "Pago iniciado desde el portal antes de contactar al proveedor"
                        : "Pago interno creado antes de contactar al proveedor");
        if (actor != null) {
            audit.record(actor, certificationLive ? AdminAuditAction.ADMIN_CREATE_PAYMENT_PROVIDER_CERTIFICATION_LIVE_CHECKOUT : AdminAuditAction.ADMIN_CREATE_TEST_PAYMENT,
                    AdminAuditEntityType.PAYMENT, payment.getId(), null, auditSnapshot(payment),
                    certificationLive ? certificationReason : "Checkout de prueba creado mediante " + provider);
        }
        return new PreparedPayment(payment.getId(), gatewayRequest(payment), true);
    }

    @Transactional
    public PaymentRecord attachProviderResult(Long paymentId, PaymentGateway.PaymentCreationResult result) {
        PaymentRecord payment = lock(paymentId);
        if (result.providerPaymentId() != null && payment.getProviderPaymentId() != null && !payment.getProviderPaymentId().equals(result.providerPaymentId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "El pago ya tiene una referencia de pago diferente del proveedor");
        }
        if (result.providerCheckoutId() != null && payment.getProviderCheckoutId() != null && !payment.getProviderCheckoutId().equals(result.providerCheckoutId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "El pago ya tiene un checkout diferente del proveedor");
        }
        if (result.providerPaymentId() != null) payment.setProviderPaymentId(result.providerPaymentId());
        if (result.providerSubscriptionId() != null) payment.setProviderSubscriptionId(result.providerSubscriptionId());
        if (result.providerCheckoutId() != null) payment.setProviderCheckoutId(result.providerCheckoutId());
        if (result.checkoutUrl() != null) payment.setCheckoutUrl(result.checkoutUrl());
        payment = applyStatus(payment, result.status(), result.providerStatus(), PaymentEventSource.PROVIDER_SYNC,
                "Respuesta de creación del proveedor", false);
        return payment;
    }

    @Transactional
    public PaymentRecord applyProviderStatusAdmin(Long paymentId, PaymentGateway.PaymentStatusResult result,
                                                  PaymentEventSource source, BackofficeUser actor,
                                                  AdminAuditAction action, String reason) {
        PaymentRecord payment = lock(paymentId);
        Map<String, Object> before = auditSnapshot(payment);
        if (payment.getProviderPaymentId() == null && result.providerPaymentId() != null) payment.setProviderPaymentId(result.providerPaymentId());
        boolean refundIncreased = updateProviderRefundedAmount(payment, result.refundedAmount());
        payment = applyStatus(payment, result.status(), result.providerStatus(), source, null, refundIncreased);
        audit.record(actor, action, AdminAuditEntityType.PAYMENT, payment.getId(), before, auditSnapshot(payment), reason);
        return payment;
    }

    @Transactional
    public PaymentRecord applyProviderStatusWebhook(Long paymentId, PaymentGateway.PaymentStatusResult result, String note) {
        PaymentRecord payment = lock(paymentId);
        if (payment.getProviderPaymentId() == null && result.providerPaymentId() != null) payment.setProviderPaymentId(result.providerPaymentId());
        boolean refundIncreased = updateProviderRefundedAmount(payment, result.refundedAmount());
        return applyStatus(payment, result.status(), result.providerStatus(), PaymentEventSource.WEBHOOK, note, refundIncreased);
    }

    @Transactional
    public PaymentRecord applyProviderStatusRefund(Long paymentId, PaymentGateway.PaymentStatusResult result, String note, BigDecimal requestedAmount) {
        PaymentRecord payment = lock(paymentId);
        if (payment.getProviderPaymentId() == null && result.providerPaymentId() != null) payment.setProviderPaymentId(result.providerPaymentId());
        BigDecimal beforeRefunded = payment.getProviderRefundedAmount() == null ? BigDecimal.ZERO : payment.getProviderRefundedAmount();
        boolean refundIncreased = updateProviderRefundedAmount(payment, result.refundedAmount());
        if (!refundIncreased && requestedAmount != null && requestedAmount.signum() > 0) {
            BigDecimal inferred = beforeRefunded.add(requestedAmount).min(payment.getAmount()).setScale(2, RoundingMode.HALF_UP);
            payment.setProviderRefundedAmount(inferred);
            refundIncreased = inferred.compareTo(beforeRefunded) > 0;
        }
        return applyStatus(payment, result.status(), result.providerStatus(), PaymentEventSource.REFUND, note, refundIncreased);
    }

    @Transactional(readOnly = true)
    public PaymentRecord detailEntity(Long paymentId) {
        return payments.findDetailedById(requireId(paymentId)).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pago no encontrado"));
    }
    @Transactional(readOnly = true)
    public AutomaticTarget automaticTarget(Long paymentId) {
        PaymentRecord p = detailEntity(paymentId);
        return new AutomaticTarget(p.getId(), p.getProvider(), new PaymentGateway.PaymentLookupRequest(
                p.getProviderPaymentId(), p.getProviderSubscriptionId(), p.getProviderCheckoutId(),
                p.getMerchantReference(), p.getIdempotencyKey() + ":auto-reconcile"));
    }

    @Transactional
    public boolean applyProviderStatusAutomatic(Long paymentId, PaymentGateway.PaymentStatusResult result) {
        PaymentRecord p = lock(paymentId);
        PaymentStatus beforeStatus = p.getStatus();
        String beforeProviderStatus = p.getProviderStatus();
        BigDecimal beforeRefunded = p.getProviderRefundedAmount();
        OffsetDateTime beforeFulfilled = p.getFulfilledAt();
        p.setLastReconciliationAttemptAt(OffsetDateTime.now(ZoneOffset.UTC));
        p.setLastReconciliationError(null);
        if (p.getProviderPaymentId() == null && result.providerPaymentId() != null) p.setProviderPaymentId(result.providerPaymentId());
        boolean refundIncreased = updateProviderRefundedAmount(p, result.refundedAmount());
        p = applyStatus(p, result.status(), result.providerStatus(), PaymentEventSource.AUTOMATIC_RECONCILIATION,
                "Conciliación preventiva automática contra el proveedor", refundIncreased);
        return beforeStatus != p.getStatus()
                || !java.util.Objects.equals(beforeProviderStatus, p.getProviderStatus())
                || !java.util.Objects.equals(beforeRefunded, p.getProviderRefundedAmount())
                || !java.util.Objects.equals(beforeFulfilled, p.getFulfilledAt());
    }

    @Transactional
    public PaymentRecord applyProviderStatusOwnerRefresh(Long paymentId, PaymentGateway.PaymentStatusResult result) {
        PaymentRecord p = lock(paymentId);
        p.setLastReconciliationAttemptAt(OffsetDateTime.now(ZoneOffset.UTC));
        p.setLastReconciliationError(null);
        if (p.getProviderPaymentId() == null && result.providerPaymentId() != null) p.setProviderPaymentId(result.providerPaymentId());
        boolean refundIncreased = updateProviderRefundedAmount(p, result.refundedAmount());
        return applyStatus(p, result.status(), result.providerStatus(), PaymentEventSource.PROVIDER_SYNC,
                "Actualización solicitada por el portal después del retorno del checkout", refundIncreased);
    }

    @Transactional
    public void recordOwnerRefreshFailure(Long paymentId, RuntimeException error) {
        PaymentRecord p = lock(paymentId);
        p.setLastReconciliationAttemptAt(OffsetDateTime.now(ZoneOffset.UTC));
        p.setLastReconciliationError(safeReconciliationError(error));
        payments.save(p);
    }

    @Transactional
    public void recordAutomaticFailure(Long paymentId, RuntimeException error) {
        PaymentRecord p = lock(paymentId);
        p.setLastReconciliationAttemptAt(OffsetDateTime.now(ZoneOffset.UTC));
        p.setLastReconciliationError(safeReconciliationError(error));
        payments.save(p);
    }


    private PaymentRecord prepareSubscription(PaymentProvider provider, PaymentProviderMode providerMode, CreateInput input, BackofficeUser actor, String key, OffsetDateTime now, Long marketplaceUserId) {
        User user = users.findByIdForUpdate(requireId(input.userId())).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuario no encontrado"));
        requireMarketplace(user);
        if (marketplaceUserId != null && !marketplaceUserId.equals(user.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No puedes iniciar una suscripción para otra cuenta");
        }
        if (user.isSuspended()) throw new ResponseStatusException(HttpStatus.CONFLICT, "No se puede iniciar un pago para una cuenta suspendida");
        int months = input.subscriptionPeriodMonths() == null ? 1 : input.subscriptionPeriodMonths();
        if (months < 1 || months > 12) throw bad("El período de suscripción debe estar entre 1 y 12 meses");
        Long versionId = requireId(input.planVersionId());
        Long planId = planVersions.findPlanIdByVersionId(versionId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Versión de plan no encontrada"));
        Plan plan = plans.findByIdForUpdate(planId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Plan no encontrado"));
        if ("FREE".equalsIgnoreCase(plan.getCode())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "La prueba gratuita no es un plan comprable. Selecciona un plan pago disponible");
        }
        PlanVersion version = planVersions.findById(versionId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Versión de plan no encontrada"));
        if (!plan.isActive() || version.getStatus() != PlanVersionStatus.PUBLISHED || version.getEffectiveFrom() == null || now.isBefore(version.getEffectiveFrom()) || (version.getEffectiveUntil() != null && !now.isBefore(version.getEffectiveUntil()))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "La versión del plan no está disponible para un nuevo pago");
        }
        if (marketplaceUserId != null) {
            boolean samePlanActive = subscriptions.findEffectiveActiveDetailed(user.getId(), now, SubscriptionStatus.ACTIVE).stream()
                    .map(OwnerSubscription::getPlanVersion)
                    .filter(java.util.Objects::nonNull)
                    .map(PlanVersion::getPlan)
                    .filter(java.util.Objects::nonNull)
                    .anyMatch(activePlan -> plan.getId().equals(activePlan.getId()));
            if (samePlanActive) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Ya tienes este plan activo. Puedes cambiar a otro plan disponible");
            }
        }
        PlanVersionPeriodPrice periodPrice = (version.getPeriodPrices() == null ? List.<PlanVersionPeriodPrice>of() : version.getPeriodPrices()).stream()
                .filter(item -> item.getPeriodMonths() == months && item.isEnabled())
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "El período seleccionado no está disponible para este plan"));
        BigDecimal amount = periodPrice.getTotalPrice() == null
                ? BigDecimal.ZERO
                : periodPrice.getTotalPrice().setScale(2, RoundingMode.HALF_UP);
        if (amount.signum() <= 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Una versión de precio cero no debe procesarse como pago");
        return base(provider, providerMode, actor, key, PaymentPurpose.SUBSCRIPTION, user, amount, version.getCurrency())
                .planVersion(version).subscriptionPeriodMonths(months).build();
    }

    private PaymentRecord preparePromotion(PaymentProvider provider, PaymentProviderMode providerMode, CreateInput input, BackofficeUser actor, String key, OffsetDateTime now, Long marketplaceUserId) {
        Pension pension = pensions.findByIdForEntitlementUpdate(requireId(input.pensionId())).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pensión no encontrada"));
        User owner = pension.getOwner() != null ? pension.getOwner() : pension.getCreatedBy();
        requireMarketplace(owner);
        if (marketplaceUserId != null && !marketplaceUserId.equals(owner.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Solo el propietario responsable de la pensión puede comprar un destacado");
        }
        if (pension.getStatus() != PensionStatus.PUBLISHED || Boolean.TRUE.equals(pension.getModerationBlocked()) || owner.isSuspended()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "La pensión debe estar publicada, sin bloqueo y con propietario activo");
        }
        Long versionId = requireId(input.promotionProductVersionId());
        Long productId = promotionVersions.findProductIdByVersionId(versionId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Versión del destacado no encontrada"));
        PromotionProduct product = promotionProducts.findByIdForUpdate(productId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Producto de destacado no encontrado"));
        PromotionProductVersion version = promotionVersions.findById(versionId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Versión del destacado no encontrada"));
        if (product.getTargetType() != PromotionTargetType.GLOBAL && product.getTargetType() != PromotionTargetType.STUDY_CENTER) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Ese tipo de destacado todavía no está disponible para compras");
        }
        if (product.getDurationDays() <= 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "El producto de destacado tiene una duración inválida");
        }
        if (!product.isActive() || version.getStatus() != PromotionProductVersionStatus.PUBLISHED || version.getEffectiveFrom() == null || now.isBefore(version.getEffectiveFrom()) || (version.getEffectiveUntil() != null && !now.isBefore(version.getEffectiveUntil()))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "La versión del destacado no está disponible para un nuevo pago");
        }
        StudyCenterCatalog center = null;
        if (product.getTargetType() == PromotionTargetType.STUDY_CENTER) {
            center = studyCenters.findById(requireId(input.studyCenterId())).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Centro de estudio no encontrado"));
            if (!Boolean.TRUE.equals(center.getActive()) || !Boolean.TRUE.equals(center.getVerified()) || center.getLat() == null || center.getLng() == null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "El centro debe estar activo, verificado y geolocalizado");
            }
        } else if (input.studyCenterId() != null) {
            throw bad("El producto seleccionado no utiliza centro de estudio");
        }
        if (marketplaceUserId != null) {
            Long centerId = center == null ? null : center.getId();
            long overlapping = promotions.countOverlapping(
                    pension.getId(), product.getTargetType().name(), centerId, now, now.plusDays(product.getDurationDays()));
            if (overlapping > 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Ya existe un destacado vigente o programado que se solapa con esta compra");
            }
        }
        if (version.getPrice().signum() <= 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Una versión de precio cero no debe procesarse como pago");
        return base(provider, providerMode, actor, key, PaymentPurpose.PROMOTION, owner, version.getPrice(), version.getCurrency())
                .pension(pension).promotionProductVersion(version).studyCenter(center).build();
    }


    private Map<String, Object> auditSnapshot(PaymentRecord p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("status", p.getStatus());
        m.put("providerStatus", p.getProviderStatus());
        m.put("providerMode", p.getProviderMode());
        m.put("providerCheckoutId", p.getProviderCheckoutId());
        m.put("purpose", p.getPurpose());
        m.put("amount", p.getAmount());
        m.put("currency", p.getCurrency());
        m.put("providerRefundedAmount", p.getProviderRefundedAmount());
        m.put("refundBenefitDecision", p.getRefundBenefitDecision());
        m.put("fulfilledAt", p.getFulfilledAt());
        m.put("fulfillmentErrorCode", p.getFulfillmentErrorCode());
        return m;
    }

    private void assertIdempotentMatch(PaymentRecord existing, PaymentProvider provider, PaymentProviderMode providerMode, CreateInput input) {
        boolean modeMatches = existing.getProviderMode() == providerMode
                || (existing.getProviderMode() == null && providerMode != PaymentProviderMode.LIVE);
        boolean same = existing.getProvider() == provider && modeMatches && existing.getPurpose() == input.purpose();
        if (same && input.purpose() == PaymentPurpose.SUBSCRIPTION) {
            int months = input.subscriptionPeriodMonths() == null ? 1 : input.subscriptionPeriodMonths();
            same = existing.getUser().getId().equals(input.userId())
                    && existing.getPlanVersion() != null && existing.getPlanVersion().getId().equals(input.planVersionId())
                    && java.util.Objects.equals(existing.getSubscriptionPeriodMonths(), months);
        } else if (same && input.purpose() == PaymentPurpose.PROMOTION) {
            same = (input.userId() == null || existing.getUser().getId().equals(input.userId()))
                    && existing.getPension() != null && existing.getPension().getId().equals(input.pensionId())
                    && existing.getPromotionProductVersion() != null
                    && existing.getPromotionProductVersion().getId().equals(input.promotionProductVersionId())
                    && java.util.Objects.equals(existing.getStudyCenter() == null ? null : existing.getStudyCenter().getId(), input.studyCenterId());
        }
        if (!same) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "La clave de idempotencia ya fue utilizada con un pago diferente");
        }
    }

    private PaymentRecord.PaymentRecordBuilder base(PaymentProvider provider, PaymentProviderMode providerMode, BackofficeUser actor, String key, PaymentPurpose purpose, User user, BigDecimal amount, String currency) {
        return PaymentRecord.builder().user(user).purpose(purpose).provider(provider).providerMode(providerMode)
                .merchantReference("pay_" + UUID.randomUUID()).idempotencyKey(key)
                .amount(amount.setScale(2, RoundingMode.HALF_UP)).currency(currency.toUpperCase())
                .status(PaymentStatus.CREATED).createdByBackoffice(actor);
    }

    private PaymentGateway.PaymentCreationRequest gatewayRequest(PaymentRecord p) {
        Map<String,String> metadata = new LinkedHashMap<>();
        metadata.put("internalPaymentId", p.getId() == null ? "pending" : p.getId().toString());
        if (p.getPlanVersion() != null) metadata.put("planVersionId", p.getPlanVersion().getId().toString());
        if (p.getPension() != null) metadata.put("pensionId", p.getPension().getId().toString());
        String description = p.getPurpose() == PaymentPurpose.SUBSCRIPTION ? "Suscripción de Pensiones" : "Destacado de Pensiones";
        return new PaymentGateway.PaymentCreationRequest(p.getMerchantReference(), p.getIdempotencyKey(), p.getPurpose(), p.getUser().getId(), p.getUser().getEmail(), p.getAmount(), p.getCurrency(), description, metadata);
    }

    private PaymentRecord applyStatus(PaymentRecord p, PaymentStatus target, String providerStatus, PaymentEventSource source, String note, boolean refundIncreased) {
        if (target == null) throw new IllegalArgumentException("El proveedor devolvió un estado nulo");
        PaymentStatus before = p.getStatus();
        OffsetDateTime beforeFulfilledAt = p.getFulfilledAt();
        Long beforeFulfilledSubscriptionId = p.getFulfilledSubscription() == null ? null : p.getFulfilledSubscription().getId();
        if (before != target && before.isTerminal()
                && !(before == PaymentStatus.APPROVED && target == PaymentStatus.REFUNDED)) return p;
        p.setProviderStatus(providerStatus);
        p.setLastProviderSyncAt(OffsetDateTime.now(ZoneOffset.UTC));
        p.setLastReconciliationAttemptAt(OffsetDateTime.now(ZoneOffset.UTC));
        p.setLastReconciliationError(null);
        boolean statusChanged = before != target;
        if (statusChanged) {
            p.setStatus(target);
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            switch (target) {
                case APPROVED -> { if (p.getApprovedAt() == null) p.setApprovedAt(now); }
                case REJECTED -> p.setRejectedAt(now);
                case CANCELLED -> p.setCancelledAt(now);
                case REFUNDED -> p.setRefundedAt(now);
                case EXPIRED -> p.setExpiredAt(now);
                default -> { }
            }
            addHistory(p, before, target, providerStatus, source, note);
        }

        boolean partialRefund = providerStatus != null && providerStatus.contains("REVIEW_PARTIAL_REFUND");
        if (p.getFulfilledAt() != null && target == PaymentStatus.REFUNDED && (statusChanged || refundIncreased)) {
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            fulfillment.revokeAfterFullRefund(p, now);
            p.setRefundBenefitDecision(RefundBenefitDecision.REVOKE_BENEFIT);
            p.setRefundBenefitDecidedAt(now);
            p.setRefundBenefitDecidedByBackoffice(null);
            p.setRefundBenefitReason("Revocación automática por reembolso total confirmado por el proveedor");
            p.setFulfillmentErrorCode(null);
            p.setFulfillmentErrorMessage(null);
        } else if (p.getFulfilledAt() != null && partialRefund && refundIncreased) {
            p.setRefundBenefitDecision(null);
            p.setRefundBenefitDecidedAt(null);
            p.setRefundBenefitDecidedByBackoffice(null);
            p.setRefundBenefitReason(null);
            p.setFulfillmentErrorCode("PARTIAL_REFUND_REQUIRES_REVIEW");
            p.setFulfillmentErrorMessage("El proveedor informa una devolución parcial; decidí explícitamente el tratamiento del beneficio concedido");
        }

        if (p.getStatus() == PaymentStatus.APPROVED && p.getFulfilledAt() == null
                && !partialRefund) {
            if (!paymentRuntime.paymentsEnabled()) {
                p.setFulfillmentErrorCode("PAYMENTS_MASTER_DISABLED");
                p.setFulfillmentErrorMessage("El pago fue aprobado, pero la configuración efectiva de pagos impide conceder beneficios hasta reactivarlos y reconciliarlo");
            } else {
                var result = fulfillment.fulfill(p, OffsetDateTime.now(ZoneOffset.UTC));
                if (result.fulfilled()) {
                    p.setFulfilledAt(OffsetDateTime.now(ZoneOffset.UTC));
                    p.setFulfillmentErrorCode(null); p.setFulfillmentErrorMessage(null);
                    p.setFulfilledSubscription(result.subscription()); p.setFulfilledPromotion(result.promotion());
                } else {
                    p.setFulfillmentErrorCode(result.errorCode()); p.setFulfillmentErrorMessage(result.errorMessage());
                }
            }
        }
        PaymentRecord saved = payments.save(p);
        boolean fulfillmentChanged = !java.util.Objects.equals(beforeFulfilledAt, saved.getFulfilledAt())
                || !java.util.Objects.equals(beforeFulfilledSubscriptionId,
                saved.getFulfilledSubscription() == null ? null : saved.getFulfilledSubscription().getId());
        if (statusChanged || fulfillmentChanged || refundIncreased) {
            publishRealtimeChanges(saved, statusChanged, fulfillmentChanged);
        }
        return saved;
    }

    private void publishRealtimeChanges(PaymentRecord payment, boolean statusChanged, boolean fulfillmentChanged) {
        if (payment == null || payment.getUser() == null || payment.getUser().getId() == null || realtimeEvents == null) return;
        Map<String, Object> paymentData = new LinkedHashMap<>();
        paymentData.put("status", payment.getStatus() == null ? null : payment.getStatus().name());
        paymentData.put("purpose", payment.getPurpose() == null ? null : payment.getPurpose().name());
        paymentData.put("benefitApplied", payment.getFulfilledAt() != null);
        paymentData.put("fulfillmentPending", payment.getStatus() == PaymentStatus.APPROVED && payment.getFulfilledAt() == null);
        paymentData.put("providerStatus", payment.getProviderStatus());
        paymentData.put("statusChanged", statusChanged);
        realtimeEvents.publishToUser(payment.getUser().getId(), "PAYMENT_STATUS_CHANGED", payment.getId(), paymentData);

        if (payment.getPurpose() == PaymentPurpose.SUBSCRIPTION
                && (fulfillmentChanged || payment.getStatus() == PaymentStatus.REFUNDED)) {
            Map<String, Object> subscriptionData = new LinkedHashMap<>();
            OwnerSubscription subscription = payment.getFulfilledSubscription();
            subscriptionData.put("paymentId", payment.getId());
            subscriptionData.put("subscriptionId", subscription == null ? null : subscription.getId());
            subscriptionData.put("status", subscription == null || subscription.getStatus() == null
                    ? (payment.getStatus() == PaymentStatus.REFUNDED ? "CANCELLED" : null)
                    : subscription.getStatus().name());
            subscriptionData.put("reason", payment.getStatus() == PaymentStatus.REFUNDED ? "FULL_REFUND" : "PAYMENT_FULFILLMENT");
            realtimeEvents.publishToUser(payment.getUser().getId(), "SUBSCRIPTION_CHANGED",
                    subscription == null ? null : subscription.getId(), subscriptionData);
        }
    }

    private boolean updateProviderRefundedAmount(PaymentRecord payment, BigDecimal providerAmount) {
        if (providerAmount == null || providerAmount.signum() < 0) return false;
        BigDecimal before = payment.getProviderRefundedAmount() == null ? BigDecimal.ZERO : payment.getProviderRefundedAmount();
        BigDecimal normalized = providerAmount.setScale(2, RoundingMode.HALF_UP);
        if (normalized.compareTo(before) < 0) return false;
        payment.setProviderRefundedAmount(normalized);
        return normalized.compareTo(before) > 0;
    }

    private void addHistory(PaymentRecord payment, PaymentStatus from, PaymentStatus to, String providerStatus, PaymentEventSource source, String note) {
        history.save(PaymentStatusHistory.builder().payment(payment).fromStatus(from).toStatus(to).providerStatus(providerStatus).eventSource(source).note(note).build());
    }

    private PaymentRecord lock(Long id) { return payments.findByIdForUpdate(requireId(id)).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pago no encontrado")); }
    private String safeReconciliationError(RuntimeException error) {
        String value = error instanceof ResponseStatusException r ? r.getReason() : error.getMessage();
        if (value == null || value.isBlank()) value = "No se pudo consultar el estado del pago en el proveedor";
        return value.length() <= 600 ? value : value.substring(0, 600);
    }
    private Long requireId(Long id) { if (id == null || id <= 0) throw bad("Identificador inválido"); return id; }
    private void requireBackofficeActor(BackofficeUser actor) {
        if (actor == null || actor.getId() == null) throw new IllegalArgumentException("El checkout administrativo requiere un usuario interno persistido");
    }
    private String cleanKey(String v) { if (v == null || v.isBlank()) throw bad("La clave de idempotencia es obligatoria"); String x=v.trim(); if(x.length()>120) throw bad("La clave de idempotencia es demasiado larga"); return x; }
    private void requireMarketplace(User u) { if (u == null || (u.getRole()!=UserRole.SEEKER && u.getRole()!=UserRole.OWNER)) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Usuario del marketplace no encontrado"); }
    private ResponseStatusException bad(String m) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,m); }

    public record CreateInput(PaymentPurpose purpose, Long userId, Long planVersionId, Integer subscriptionPeriodMonths,
                              Long pensionId, Long promotionProductVersionId, Long studyCenterId, String idempotencyKey) {}
    public record PreparedPayment(Long paymentId, PaymentGateway.PaymentCreationRequest gatewayRequest, boolean newlyCreated) {}
    public record AutomaticTarget(Long paymentId, PaymentProvider provider, PaymentGateway.PaymentLookupRequest lookup) {}
}
