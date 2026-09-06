package uy.pensiones.payment;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import uy.pensiones.enums.PaymentProvider;

@Component
public class PaymentConfigurationValidator implements ApplicationRunner {

    private final PaymentGatewayRegistry registry;
    private final PaymentRuntimeConfigurationService runtime;
    private final MercadoPagoPaymentGateway mercadoPago;

    public PaymentConfigurationValidator(PaymentGatewayRegistry registry, PaymentRuntimeConfigurationService runtime, MercadoPagoPaymentGateway mercadoPago) {
        this.registry = registry; this.runtime = runtime; this.mercadoPago = mercadoPago;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (runtime.isProd() && registry.isConfiguredEnabled(PaymentProvider.MOCK)) {
            throw new IllegalStateException("El proveedor MOCK no puede quedar habilitado en BD con el perfil prod");
        }
        for (PaymentProvider provider : PaymentProvider.values()) {
            if (registry.isConfiguredEnabled(provider) && !registry.isImplemented(provider)) {
                throw new IllegalStateException("El proveedor de pagos " + provider + " está habilitado en BD pero no tiene implementación PaymentGateway");
            }
        }
        if (registry.isConfiguredEnabled(PaymentProvider.MERCADO_PAGO)) {
            try { mercadoPago.validateLocalConfiguration(); }
            catch (RuntimeException e) { throw new IllegalStateException("Mercado Pago está habilitado pero su configuración local es inválida: " + e.getMessage(), e); }
        }
    }
}
