package uy.pensiones.payment;

import org.springframework.stereotype.Component;
import uy.pensiones.enums.PaymentProvider;
import uy.pensiones.enums.PaymentPurpose;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class PaymentGatewayRegistry {

    private final Map<PaymentProvider, PaymentGateway> gateways = new EnumMap<>(PaymentProvider.class);
    private final PaymentRuntimeConfigurationService runtime;

    public PaymentGatewayRegistry(List<PaymentGateway> gatewayBeans, PaymentRuntimeConfigurationService runtime) {
        this.runtime = runtime;
        for (PaymentGateway gateway : gatewayBeans) {
            PaymentGateway previous = gateways.put(gateway.provider(), gateway);
            if (previous != null) throw new IllegalStateException("Existe más de una implementación PaymentGateway para " + gateway.provider());
        }
    }

    public PaymentGateway requireEnabled(PaymentProvider provider) {
        runtime.requireProviderEnabled(provider, null);
        return requireImplemented(provider);
    }

    public PaymentGateway requireEnabled(PaymentProvider provider, PaymentPurpose purpose) {
        runtime.requireProviderEnabled(provider, purpose);
        return requireImplemented(provider);
    }

    public PaymentGateway requireImplemented(PaymentProvider provider) {
        PaymentGateway gateway = gateways.get(provider);
        if (gateway == null) throw new IllegalStateException("No existe una implementación PaymentGateway para " + provider);
        return gateway;
    }

    public boolean isImplemented(PaymentProvider provider) { return gateways.containsKey(provider); }

    /** Estado persistido del proveedor, sin considerar el master global ni la implementación. */
    public boolean isConfiguredEnabled(PaymentProvider provider) { return runtime.provider(provider).isEnabled(); }

    public boolean isAvailableForNewPayments(PaymentProvider provider, PaymentPurpose purpose) {
        return isImplemented(provider) && runtime.providerAvailableForNewPayments(provider, purpose);
    }

    public boolean isAvailableForNewPayments(PaymentProvider provider, PaymentPurpose purpose,
                                             String currency, String countryCode) {
        return isImplemented(provider)
                && runtime.providerAvailableForNewPayments(provider, purpose, currency, countryCode);
    }
}
