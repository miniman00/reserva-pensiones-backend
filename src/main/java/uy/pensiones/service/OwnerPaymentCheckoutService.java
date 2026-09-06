package uy.pensiones.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import uy.pensiones.config.AppProperties;
import uy.pensiones.enums.PaymentProvider;
import uy.pensiones.enums.PaymentPurpose;
import uy.pensiones.enums.PaymentStatus;
import uy.pensiones.enums.UserRole;
import uy.pensiones.model.PaymentProviderConfig;
import uy.pensiones.model.PaymentRecord;
import uy.pensiones.model.PaymentSettings;
import uy.pensiones.model.Pension;
import uy.pensiones.model.PlanVersion;
import uy.pensiones.model.PromotionProductVersion;
import uy.pensiones.model.User;
import uy.pensiones.payment.PaymentGateway;
import uy.pensiones.payment.PaymentGatewayRegistry;
import uy.pensiones.payment.PaymentRuntimeConfigurationService;
import uy.pensiones.repo.PaymentProviderConfigRepository;
import uy.pensiones.repo.PensionRepository;
import uy.pensiones.repo.PlanVersionRepository;
import uy.pensiones.repo.PromotionProductVersionRepository;
import uy.pensiones.repo.UserRepository;

import java.util.List;

@Service
public class OwnerPaymentCheckoutService {
    private static final Logger log = LoggerFactory.getLogger(OwnerPaymentCheckoutService.class);
    private static final int MAX_CLIENT_IDEMPOTENCY_KEY_LENGTH = 64;

    private final AppProperties properties;
    private final UserRepository users;
    private final PensionRepository pensions;
    private final PlanVersionRepository planVersions;
    private final PromotionProductVersionRepository promotionVersions;
    private final PaymentProviderConfigRepository providerConfigs;
    private final PaymentRuntimeConfigurationService paymentRuntime;
    private final PaymentGatewayRegistry gateways;
    private final PaymentTransactionService transactions;

    public OwnerPaymentCheckoutService(AppProperties properties,
                                       UserRepository users,
                                       PensionRepository pensions,
                                       PlanVersionRepository planVersions,
                                       PromotionProductVersionRepository promotionVersions,
                                       PaymentProviderConfigRepository providerConfigs,
                                       PaymentRuntimeConfigurationService paymentRuntime,
                                       PaymentGatewayRegistry gateways,
                                       PaymentTransactionService transactions) {
        this.properties = properties;
        this.users = users;
        this.pensions = pensions;
        this.planVersions = planVersions;
        this.promotionVersions = promotionVersions;
        this.providerConfigs = providerConfigs;
        this.paymentRuntime = paymentRuntime;
        this.gateways = gateways;
        this.transactions = transactions;
    }

    public CheckoutResponse createSubscriptionCheckout(Long userId, Long planVersionId,
                                                       Integer subscriptionPeriodMonths,
                                                       String clientIdempotencyKey) {
        requireMonetizationEnabled();
        User user = requireMarketplaceUser(userId);
        PlanVersion version = planVersions.findById(requireId(planVersionId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Versión de plan no encontrada"));
        String idempotencyKey = scopedIdempotencyKey(user.getId(), clientIdempotencyKey);
        PaymentProvider provider = selectProvider(PaymentPurpose.SUBSCRIPTION, version.getCurrency(), user.getCountryCode());

        var input = new PaymentTransactionService.CreateInput(
                PaymentPurpose.SUBSCRIPTION, user.getId(), version.getId(), subscriptionPeriodMonths,
                null, null, null, idempotencyKey);
        return executeCheckout(provider, input, user.getId());
    }

    public CheckoutResponse createPromotionCheckout(Long userId, Long pensionId,
                                                     Long promotionProductVersionId, Long studyCenterId,
                                                     String clientIdempotencyKey) {
        requireMonetizationEnabled();
        User user = requireMarketplaceUser(userId);
        Pension pension = pensions.findWithOwnerById(requireId(pensionId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pensión no encontrada"));
        User responsibleOwner = pension.getOwner() != null ? pension.getOwner() : pension.getCreatedBy();
        if (responsibleOwner == null || !user.getId().equals(responsibleOwner.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Solo el propietario responsable de la pensión puede comprar un destacado");
        }

        PromotionProductVersion version = promotionVersions.findById(requireId(promotionProductVersionId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Versión del destacado no encontrada"));
        String idempotencyKey = scopedIdempotencyKey(user.getId(), clientIdempotencyKey);
        PaymentProvider provider = selectProvider(PaymentPurpose.PROMOTION, version.getCurrency(), user.getCountryCode());

        var input = new PaymentTransactionService.CreateInput(
                PaymentPurpose.PROMOTION, user.getId(), null, null,
                pension.getId(), version.getId(), studyCenterId, idempotencyKey);
        return executeCheckout(provider, input, user.getId());
    }

    private CheckoutResponse executeCheckout(PaymentProvider provider,
                                             PaymentTransactionService.CreateInput input,
                                             Long authenticatedUserId) {
        var prepared = transactions.prepareMarketplace(provider, input, authenticatedUserId);
        PaymentRecord current = transactions.detailEntity(prepared.paymentId());

        if (current.getCheckoutUrl() != null && !current.getCheckoutUrl().isBlank()) {
            return response(current);
        }
        if (current.getProviderCheckoutId() != null || current.getProviderPaymentId() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "El intento de pago ya fue iniciado pero no tiene una URL de checkout disponible. Inicia un intento nuevo.");
        }

        try {
            PaymentGateway gateway = gateways.requireEnabled(provider, input.purpose());
            PaymentGateway.PaymentCreationResult providerResult = gateway.createPayment(prepared.gatewayRequest());
            if (providerResult.checkoutUrl() == null || providerResult.checkoutUrl().isBlank()) {
                throw new IllegalStateException("El proveedor no devolvió checkoutUrl para un pago del portal");
            }
            transactions.attachProviderResult(prepared.paymentId(), providerResult);
            return response(transactions.detailEntity(prepared.paymentId()));
        } catch (RuntimeException ex) {
            log.warn("No se pudo iniciar checkout de portal para paymentId={} purpose={}: {}",
                    prepared.paymentId(), input.purpose(), ex.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "No se pudo iniciar el pago en este momento. Puedes volver a intentarlo.");
        }
    }

    private PaymentProvider selectProvider(PaymentPurpose purpose, String currency, String countryCode) {
        paymentRuntime.requirePaymentsEnabledForMarketplace();
        PaymentSettings settings = paymentRuntime.settings();
        PaymentProvider preferred = settings.getDefaultProvider();
        if (isPublicCheckoutAvailable(preferred, purpose, currency, countryCode)) return preferred;

        List<PaymentProviderConfig> ordered = providerConfigs.findAllByOrderByPriorityAscProviderAsc();
        return ordered.stream()
                .map(PaymentProviderConfig::getProvider)
                .filter(provider -> isPublicCheckoutAvailable(provider, purpose, currency, countryCode))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "No hay una forma de pago disponible para esta compra en este momento"));
    }

    private boolean isPublicCheckoutAvailable(PaymentProvider provider, PaymentPurpose purpose,
                                              String currency, String countryCode) {
        if (provider == null || provider == PaymentProvider.MOCK) return false;
        return gateways.isAvailableForNewPayments(provider, purpose, currency, countryCode);
    }

    private User requireMarketplaceUser(Long userId) {
        User user = users.findById(requireId(userId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuario no encontrado"));
        if (user.getRole() != UserRole.OWNER && user.getRole() != UserRole.SEEKER) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuario del marketplace no encontrado");
        }
        if (user.isSuspended()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "La cuenta está suspendida y no puede iniciar compras");
        }
        return user;
    }

    private void requireMonetizationEnabled() {
        if (!properties.getMonetization().isEnabled()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Las compras comerciales todavía no están habilitadas");
        }
    }

    private String scopedIdempotencyKey(Long userId, String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La clave de idempotencia es obligatoria");
        }
        String clean = raw.trim();
        if (clean.length() > MAX_CLIENT_IDEMPOTENCY_KEY_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La clave de idempotencia no puede superar " + MAX_CLIENT_IDEMPOTENCY_KEY_LENGTH + " caracteres");
        }
        return "portal:" + userId + ":" + clean;
    }

    private Long requireId(Long id) {
        if (id == null || id <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Identificador inválido");
        return id;
    }

    private CheckoutResponse response(PaymentRecord payment) {
        String checkoutUrl = payment.getCheckoutUrl();
        if (checkoutUrl == null || checkoutUrl.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "El intento de pago no tiene una URL de checkout disponible");
        }
        PaymentStatus status = payment.getStatus();
        return new CheckoutResponse(payment.getId(), payment.getPurpose(), status, checkoutUrl);
    }

    public record CheckoutResponse(Long paymentId, PaymentPurpose purpose, PaymentStatus status, String checkoutUrl) {}
}
