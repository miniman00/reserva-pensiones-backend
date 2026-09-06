package uy.pensiones.enums;

import java.util.Locale;

/** Proveedores soportados por el contrato interno. Tener un enum no implica que exista una implementación activa. */
public enum PaymentProvider {
    MOCK("mock"),
    MERCADO_PAGO("mercado-pago"),
    STRIPE("stripe"),
    DLOCAL("dlocal"),
    PAYPAL("paypal");

    private final String configKey;

    PaymentProvider(String configKey) {
        this.configKey = configKey;
    }

    public String configKey() {
        return configKey;
    }

    public static PaymentProvider fromConfigKey(String value) {
        if (value == null) return null;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (PaymentProvider provider : values()) {
            if (provider.configKey.equals(normalized)) return provider;
        }
        return null;
    }
}
