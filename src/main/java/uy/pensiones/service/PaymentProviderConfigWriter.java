package uy.pensiones.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uy.pensiones.enums.*;
import uy.pensiones.model.BackofficeUser;
import uy.pensiones.model.PaymentProviderConfig;
import uy.pensiones.repo.PaymentProviderConfigRepository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class PaymentProviderConfigWriter {
    private final PaymentProviderConfigRepository providers;
    private final AdminAuditService audit;

    public PaymentProviderConfigWriter(PaymentProviderConfigRepository providers, AdminAuditService audit) {
        this.providers = providers; this.audit = audit;
    }

    @Transactional
    public void recordWebhook(PaymentProvider provider) {
        PaymentProviderConfig config = providers.findByProviderForUpdate(provider)
                .orElseThrow(() -> new IllegalStateException("Falta configuración para " + provider));
        config.setLastWebhookAt(OffsetDateTime.now(ZoneOffset.UTC));
        providers.save(config);
    }

    @Transactional
    public void recordConnectionTest(PaymentProvider provider, boolean success, String message, BackofficeUser actor) {
        PaymentProviderConfig config = providers.findByProviderForUpdate(provider)
                .orElseThrow(() -> new IllegalStateException("Falta configuración para " + provider));
        Map<String,Object> before = snapshot(config);
        config.setLastConnectivityCheckAt(OffsetDateTime.now(ZoneOffset.UTC));
        config.setLastConnectivityCheckSuccess(success);
        config.setLastConnectivityCheckMessage(trim(message, 500));
        config.setUpdatedByBackoffice(actor);
        providers.save(config);
        audit.record(actor, AdminAuditAction.ADMIN_TEST_PAYMENT_PROVIDER_CONNECTION,
                AdminAuditEntityType.PAYMENT_PROVIDER, provider.name(), before, snapshot(config), null);
    }

    static Map<String,Object> snapshot(PaymentProviderConfig c) {
        Map<String,Object> m = new LinkedHashMap<>();
        m.put("provider", c.getProvider()); m.put("displayName", c.getDisplayName()); m.put("enabled", c.isEnabled());
        m.put("mode", c.getMode()); m.put("priority", c.getPriority());
        m.put("subscriptionsEnabled", c.isSubscriptionsEnabled()); m.put("promotionsEnabled", c.isPromotionsEnabled());
        m.put("supportedCurrencies", c.getSupportedCurrencies()); m.put("supportedCountries", c.getSupportedCountries());
        m.put("configurationJson", c.getConfigurationJson());
        m.put("lastConnectivityCheckAt", c.getLastConnectivityCheckAt());
        m.put("lastConnectivityCheckSuccess", c.getLastConnectivityCheckSuccess());
        m.put("lastConnectivityCheckMessage", c.getLastConnectivityCheckMessage());
        m.put("lastWebhookAt", c.getLastWebhookAt());
        return m;
    }

    private static String trim(String value, int max) {
        if (value == null) return null;
        String x = value.trim(); return x.length() <= max ? x : x.substring(0, max);
    }
}
