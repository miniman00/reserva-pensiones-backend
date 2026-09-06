package uy.pensiones.payment;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import uy.pensiones.config.AppProperties;
import uy.pensiones.enums.PaymentProvider;
import uy.pensiones.enums.PaymentPurpose;
import uy.pensiones.model.PaymentProviderConfig;
import uy.pensiones.model.PaymentProviderCredential;
import uy.pensiones.model.PaymentSettings;
import uy.pensiones.repo.PaymentProviderConfigRepository;
import uy.pensiones.repo.PaymentProviderCredentialRepository;
import uy.pensiones.repo.PaymentSettingsRepository;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class PaymentRuntimeConfigurationService {
    public static final short SETTINGS_ID = 1;
    private final AppProperties properties;
    private final Environment environment;
    private final PaymentSettingsRepository settings;
    private final PaymentProviderConfigRepository providers;
    private final PaymentProviderCredentialRepository credentials;
    private final PaymentSecretCrypto crypto;

    public PaymentRuntimeConfigurationService(AppProperties properties, Environment environment,
                                              PaymentSettingsRepository settings, PaymentProviderConfigRepository providers,
                                              PaymentProviderCredentialRepository credentials, PaymentSecretCrypto crypto) {
        this.properties = properties; this.environment = environment; this.settings = settings;
        this.providers = providers; this.credentials = credentials; this.crypto = crypto;
    }

    public boolean infrastructureAllowed() { return properties.getPayments().isAllowed(); }
    public boolean isProd() { return environment.acceptsProfiles(Profiles.of("prod")); }

    @Transactional(readOnly = true)
    public PaymentSettings settings() {
        return settings.findById(SETTINGS_ID).orElseThrow(() -> new IllegalStateException("Falta payment_settings id=1"));
    }

    @Transactional(readOnly = true)
    public PaymentProviderConfig provider(PaymentProvider provider) {
        if (provider == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Proveedor de pagos inválido");
        return providers.findById(provider).orElseThrow(() -> new IllegalStateException("Falta configuración para " + provider));
    }

    @Transactional(readOnly = true)
    public boolean databasePaymentsEnabled() { return settings().isPaymentsEnabled(); }

    @Transactional(readOnly = true)
    public boolean paymentsEnabled() { return infrastructureAllowed() && settings().isPaymentsEnabled(); }

    @Transactional(readOnly = true)
    public boolean providerEnabled(PaymentProvider provider) {
        PaymentProviderConfig config = provider(provider);
        return config.isEnabled() && !(provider == PaymentProvider.MOCK && isProd());
    }

    @Transactional(readOnly = true)
    public boolean providerAvailableForNewPayments(PaymentProvider provider, PaymentPurpose purpose) {
        if (!paymentsEnabled()) return false;
        PaymentProviderConfig config = provider(provider);
        if (!config.isEnabled()) return false;
        if (provider == PaymentProvider.MOCK && isProd()) return false;
        if (purpose == PaymentPurpose.SUBSCRIPTION && !config.isSubscriptionsEnabled()) return false;
        if (purpose == PaymentPurpose.PROMOTION && !config.isPromotionsEnabled()) return false;
        return true;
    }

    @Transactional(readOnly = true)
    public boolean providerAvailableForNewPayments(PaymentProvider provider, PaymentPurpose purpose,
                                                   String currency, String countryCode) {
        if (!providerAvailableForNewPayments(provider, purpose)) return false;
        PaymentProviderConfig config = provider(provider);
        return containsOrUnrestricted(config.getSupportedCurrencies(), currency)
                && containsOrUnrestricted(config.getSupportedCountries(), countryCode);
    }

    @Transactional(readOnly = true)
    public void requirePaymentsEnabled() {
        if (!infrastructureAllowed()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Los pagos están bloqueados por el kill switch de infraestructura");
        if (!settings().isPaymentsEnabled()) throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Los pagos están deshabilitados desde la configuración administrativa");
    }

    @Transactional(readOnly = true)
    public void requirePaymentsEnabledForMarketplace() {
        if (!infrastructureAllowed()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Los pagos no están disponibles temporalmente");
        if (!settings().isPaymentsEnabled()) throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Las compras están temporalmente deshabilitadas");
    }

    @Transactional(readOnly = true)
    public void requireProviderEnabled(PaymentProvider provider, PaymentPurpose purpose) {
        requirePaymentsEnabled();
        PaymentProviderConfig config = provider(provider);
        if (!config.isEnabled()) throw new ResponseStatusException(HttpStatus.CONFLICT, "El proveedor " + provider + " está deshabilitado");
        if (provider == PaymentProvider.MOCK && isProd()) throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "El proveedor MOCK está prohibido en producción");
        if (purpose == PaymentPurpose.SUBSCRIPTION && !config.isSubscriptionsEnabled()) throw new ResponseStatusException(HttpStatus.CONFLICT,
                "El proveedor no está habilitado para suscripciones");
        if (purpose == PaymentPurpose.PROMOTION && !config.isPromotionsEnabled()) throw new ResponseStatusException(HttpStatus.CONFLICT,
                "El proveedor no está habilitado para promociones");
    }


    @Transactional(readOnly = true)
    public void requireProviderScope(PaymentProvider provider, PaymentPurpose purpose, String currency, String countryCode) {
        requireProviderEnabled(provider, purpose);
        PaymentProviderConfig config = provider(provider);
        if (!containsOrUnrestricted(config.getSupportedCurrencies(), currency)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "El proveedor no admite la moneda " + currency);
        }
        if (!containsOrUnrestricted(config.getSupportedCountries(), countryCode)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "El proveedor no admite el país " + countryCode);
        }
    }

    @Transactional(readOnly = true)
    public void requireProviderScopeForMarketplace(PaymentProvider provider, PaymentPurpose purpose,
                                                   String currency, String countryCode) {
        requirePaymentsEnabledForMarketplace();
        PaymentProviderConfig config = provider(provider);
        boolean available = config.isEnabled()
                && !(provider == PaymentProvider.MOCK && isProd())
                && (purpose != PaymentPurpose.SUBSCRIPTION || config.isSubscriptionsEnabled())
                && (purpose != PaymentPurpose.PROMOTION || config.isPromotionsEnabled())
                && containsOrUnrestricted(config.getSupportedCurrencies(), currency)
                && containsOrUnrestricted(config.getSupportedCountries(), countryCode);
        if (!available) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "No hay una forma de pago disponible para esta compra en este momento");
        }
    }

    /**
     * Variante estrictamente administrativa para el smoke test LIVE de una certificación.
     * Mantiene el kill switch de infraestructura y todos los controles del proveedor, pero
     * no exige abrir el master global de pagos al portal público.
     */
    @Transactional(readOnly = true)
    public void requireProviderScopeForCertification(PaymentProvider provider, PaymentPurpose purpose, String currency, String countryCode) {
        if (!infrastructureAllowed()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Los pagos están bloqueados por el kill switch de infraestructura");
        PaymentProviderConfig config = provider(provider);
        if (!config.isEnabled()) throw new ResponseStatusException(HttpStatus.CONFLICT, "El proveedor " + provider + " está deshabilitado");
        if (provider == PaymentProvider.MOCK && isProd()) throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "El proveedor MOCK está prohibido en producción");
        if (purpose == PaymentPurpose.SUBSCRIPTION && !config.isSubscriptionsEnabled()) throw new ResponseStatusException(HttpStatus.CONFLICT,
                "El proveedor no está habilitado para suscripciones");
        if (purpose == PaymentPurpose.PROMOTION && !config.isPromotionsEnabled()) throw new ResponseStatusException(HttpStatus.CONFLICT,
                "El proveedor no está habilitado para promociones");
        if (!containsOrUnrestricted(config.getSupportedCurrencies(), currency)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "El proveedor no admite la moneda " + currency);
        }
        if (!containsOrUnrestricted(config.getSupportedCountries(), countryCode)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "El proveedor no admite el país " + countryCode);
        }
    }

    private boolean containsOrUnrestricted(String csv, String value) {
        if (csv == null || csv.isBlank() || value == null || value.isBlank()) return true;
        String expected = value.trim().toUpperCase(java.util.Locale.ROOT);
        return java.util.Arrays.stream(csv.split(",")).map(String::trim).map(x -> x.toUpperCase(java.util.Locale.ROOT)).anyMatch(expected::equals);
    }

    @Transactional(readOnly = true)
    public Map<String, String> decryptedCredentials(PaymentProvider provider) {
        Map<String, String> out = new LinkedHashMap<>();
        for (PaymentProviderCredential credential : credentials.findByProviderOrderByCredentialNameAsc(provider)) {
            out.put(credential.getCredentialName(), crypto.decrypt(credential.getEncryptedValue()));
        }
        return Map.copyOf(out);
    }
}
