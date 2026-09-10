package uy.pensiones.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import uy.pensiones.enums.*;
import uy.pensiones.model.*;
import uy.pensiones.payment.*;
import uy.pensiones.repo.*;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.regex.Pattern;

@Service
public class AdminPaymentProviderService {
    private static final Pattern CREDENTIAL_NAME = Pattern.compile("[A-Z0-9][A-Z0-9_.-]{1,79}");
    private final PaymentSettingsRepository settings;
    private final PaymentProviderConfigRepository providers;
    private final PaymentProviderCredentialRepository credentials;
    private final PaymentProviderEnvironmentCheckRepository environmentChecks;
    private final PaymentRuntimeConfigurationService runtime;
    private final PaymentGatewayRegistry registry;
    private final PaymentSecretCrypto crypto;
    private final AdminAuditService audit;
    private final ObjectMapper objectMapper;
    private final PaymentProviderConfigWriter writer;
    private final PaymentOperationalAlertNotificationRepository alertNotifications;
    private final PaymentOperationalAlertEmailService alertEmailService;

    public AdminPaymentProviderService(PaymentSettingsRepository settings,
                                       PaymentProviderConfigRepository providers,
                                       PaymentProviderCredentialRepository credentials,
                                       PaymentProviderEnvironmentCheckRepository environmentChecks,
                                       PaymentRuntimeConfigurationService runtime,
                                       PaymentGatewayRegistry registry,
                                       PaymentSecretCrypto crypto,
                                       AdminAuditService audit,
                                       ObjectMapper objectMapper,
                                       PaymentProviderConfigWriter writer,
                                       PaymentOperationalAlertNotificationRepository alertNotifications,
                                       PaymentOperationalAlertEmailService alertEmailService) {
        this.settings=settings; this.providers=providers; this.credentials=credentials; this.environmentChecks=environmentChecks; this.runtime=runtime;
        this.registry=registry; this.crypto=crypto; this.audit=audit; this.objectMapper=objectMapper; this.writer=writer;
        this.alertNotifications=alertNotifications; this.alertEmailService=alertEmailService;
    }

    @Transactional(readOnly = true)
    public SettingsDTO get() {
        PaymentSettings global = runtime.settings();
        List<ProviderDTO> providerDtos = providers.findAllByOrderByPriorityAscProviderAsc().stream().map(this::dto).toList();
        return new SettingsDTO(runtime.infrastructureAllowed(), global.isPaymentsEnabled(),
                runtime.infrastructureAllowed() && global.isPaymentsEnabled(), runtime.isProd(), crypto.isReady(),
                global.getDefaultProvider(), reconciliation(global), operationalAlerts(global), providerDtos);
    }

    @Transactional
    public SettingsDTO updateGlobal(boolean enabled, PaymentProvider defaultProvider, String rawReason, BackofficeUser actor) {
        String reason = audit.requireReason(rawReason);
        PaymentSettings global = settings.findByIdForUpdate(PaymentRuntimeConfigurationService.SETTINGS_ID)
                .orElseThrow(() -> new IllegalStateException("Falta payment_settings id=1"));
        Map<String,Object> before = settingsSnapshot(global);
        if (enabled && !runtime.infrastructureAllowed()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Infraestructura mantiene APP_PAYMENTS_ALLOWED=false; el Backoffice no puede sobrepasar ese bloqueo");
        }
        if (defaultProvider != null) validateDefault(defaultProvider);
        if (enabled && providers.findAll().stream().noneMatch(p -> p.isEnabled() && registry.isImplemented(p.getProvider()) && !(p.getProvider()==PaymentProvider.MOCK && runtime.isProd()))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "No hay ningún proveedor habilitado e implementado para activar pagos");
        }
        global.setPaymentsEnabled(enabled);
        global.setDefaultProvider(defaultProvider);
        global.setUpdatedByBackoffice(actor);
        settings.save(global);
        AdminAuditAction action = before.get("paymentsEnabled").equals(enabled)
                ? AdminAuditAction.ADMIN_CHANGE_DEFAULT_PAYMENT_PROVIDER
                : (enabled ? AdminAuditAction.ADMIN_ENABLE_PAYMENTS : AdminAuditAction.ADMIN_DISABLE_PAYMENTS);
        audit.record(actor, action, AdminAuditEntityType.PAYMENT_SETTINGS, global.getId(), before, settingsSnapshot(global), reason);
        return get();
    }

    @Transactional
    public SettingsDTO updateReconciliation(boolean enabled, int intervalMinutes, int batchSize, String rawReason, BackofficeUser actor) {
        String reason = audit.requireReason(rawReason);
        if (intervalMinutes < 1 || intervalMinutes > 1440) throw bad("El intervalo debe estar entre 1 y 1440 minutos");
        if (batchSize < 1 || batchSize > 200) throw bad("El batch debe estar entre 1 y 200 elementos por tipo");
        PaymentSettings global = settings.findByIdForUpdate(PaymentRuntimeConfigurationService.SETTINGS_ID)
                .orElseThrow(() -> new IllegalStateException("Falta payment_settings id=1"));
        Map<String,Object> before = reconciliationSnapshot(global);
        global.setAutomaticReconciliationEnabled(enabled);
        global.setReconciliationIntervalMinutes(intervalMinutes);
        global.setReconciliationBatchSize(batchSize);
        global.setUpdatedByBackoffice(actor);
        settings.save(global);
        audit.record(actor, AdminAuditAction.ADMIN_UPDATE_PAYMENT_RECONCILIATION_CONFIG,
                AdminAuditEntityType.PAYMENT_SETTINGS, global.getId(), before, reconciliationSnapshot(global), reason);
        return get();
    }

    @Transactional
    public SettingsDTO updateOperationalAlerts(boolean enabled, List<String> rawRecipients, PaymentAlertSeverity minimumSeverity,
                                               int cooldownMinutes, String rawReason, BackofficeUser actor) {
        String reason = audit.requireReason(rawReason);
        if (cooldownMinutes < 5 || cooldownMinutes > 10080) throw bad("El cooldown debe estar entre 5 minutos y 7 días");
        PaymentAlertSeverity severity = minimumSeverity == null ? PaymentAlertSeverity.CRITICAL : minimumSeverity;
        List<String> recipients = normalizeEmails(rawRecipients);
        if (enabled && recipients.isEmpty()) throw bad("Debes configurar al menos un destinatario para habilitar las alertas por correo");
        PaymentSettings global = settings.findByIdForUpdate(PaymentRuntimeConfigurationService.SETTINGS_ID)
                .orElseThrow(() -> new IllegalStateException("Falta payment_settings id=1"));
        Map<String,Object> before = operationalAlertSnapshot(global);
        global.setOperationalAlertEmailEnabled(enabled);
        global.setOperationalAlertEmailRecipients(recipients.isEmpty() ? null : String.join(",", recipients));
        global.setOperationalAlertMinSeverity(severity);
        global.setOperationalAlertCooldownMinutes(cooldownMinutes);
        global.setUpdatedByBackoffice(actor);
        settings.save(global);
        audit.record(actor, AdminAuditAction.ADMIN_UPDATE_PAYMENT_ALERT_EMAIL_CONFIG,
                AdminAuditEntityType.PAYMENT_SETTINGS, global.getId(), before, operationalAlertSnapshot(global), reason);
        return get();
    }

    @Transactional
    public AlertEmailTestDTO testOperationalAlertEmail(String rawReason, BackofficeUser actor) {
        String reason = audit.requireReason(rawReason);
        PaymentSettings global = settings.findById(PaymentRuntimeConfigurationService.SETTINGS_ID)
                .orElseThrow(() -> new IllegalStateException("Falta payment_settings id=1"));
        var result = alertEmailService.sendTest(global);
        Map<String,Object> after = new LinkedHashMap<>();
        after.put("sent", result.sent());
        after.put("message", result.message());
        after.put("recipients", alertEmailService.recipients(global));
        audit.record(actor, AdminAuditAction.ADMIN_TEST_PAYMENT_ALERT_EMAIL,
                AdminAuditEntityType.PAYMENT_SETTINGS, global.getId(), operationalAlertSnapshot(global), after, reason);
        return new AlertEmailTestDTO(result.sent(), result.message());
    }

    @Transactional
    public ProviderDTO updateProvider(PaymentProvider provider, ProviderUpdate input, BackofficeUser actor) {
        if (input == null) throw bad("La configuración es obligatoria");
        String reason = audit.requireReason(input.reason());
        PaymentProviderConfig config = providers.findByProviderForUpdate(requireProvider(provider))
                .orElseThrow(() -> new IllegalStateException("Falta configuración para " + provider));
        Map<String,Object> before = PaymentProviderConfigWriter.snapshot(config);

        if (input.enabled() && !registry.isImplemented(provider)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "No se puede habilitar " + provider + " porque todavía no existe su PaymentGateway");
        }
        if (input.enabled() && provider == PaymentProvider.MOCK && runtime.isProd()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "El proveedor MOCK está prohibido en producción");
        }
        PaymentProviderMode mode = input.mode() == null ? PaymentProviderMode.TEST : input.mode();
        if (provider == PaymentProvider.MOCK && mode != PaymentProviderMode.TEST) throw bad("MOCK solo puede operar en modo TEST");
        if (provider == PaymentProvider.MERCADO_PAGO && mode == PaymentProviderMode.TEST) {
            throw bad("Mercado Pago debe operar en modo SANDBOX o LIVE");
        }
        if (input.enabled() && provider == PaymentProvider.MERCADO_PAGO) {
            String accessTokenName = MercadoPagoPaymentGateway.accessTokenCredentialName(mode);
            String webhookSecretName = MercadoPagoPaymentGateway.webhookSecretCredentialName(mode);
            boolean hasAccessToken = credentials.findByProviderAndCredentialName(provider, accessTokenName).isPresent();
            boolean hasWebhookSecret = credentials.findByProviderAndCredentialName(provider, webhookSecretName).isPresent();
            if (!hasAccessToken || !hasWebhookSecret) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Mercado Pago en " + mode + " requiere " + accessTokenName + " y " + webhookSecretName + " antes de habilitarse");
            }
            if (!Boolean.TRUE.equals(config.getLastConnectivityCheckSuccess())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Probá correctamente la conexión con Mercado Pago antes de habilitar el proveedor");
            }
        }
        if (input.enabled() && !input.subscriptionsEnabled() && !input.promotionsEnabled()) throw bad("Un proveedor habilitado debe aceptar al menos un propósito");
        int priority = input.priority(); if (priority < 1 || priority > 10000) throw bad("La prioridad debe estar entre 1 y 10000");
        String displayName = cleanRequired(input.displayName(), "El nombre", 100);
        String currencies = normalizeCodes(input.supportedCurrencies(), 3, "moneda");
        String countries = normalizeCodes(input.supportedCountries(), 2, "país");
        String configJson = normalizeJson(input.configurationJson());
        validateMercadoPagoConfiguration(provider, mode, configJson);
        boolean connectivityChanged = config.getMode() != mode
                || !Objects.equals(connectivityRelevantJson(provider, config.getMode(), config.getConfigurationJson()),
                connectivityRelevantJson(provider, mode, configJson));
        if (input.enabled() && provider == PaymentProvider.MERCADO_PAGO && connectivityChanged) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Guardá primero la configuración de Mercado Pago con el proveedor deshabilitado, probá la conexión y luego habilitalo sin cambiar esos datos");
        }

        PaymentSettings global = runtime.settings();
        if (!input.enabled() && global.getDefaultProvider() == provider) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cambiá primero el proveedor predeterminado antes de deshabilitar " + provider);
        }
        if (config.isEnabled() && !input.enabled() && global.isPaymentsEnabled()) {
            boolean anotherAvailable = providers.findAll().stream()
                    .filter(other -> other.getProvider() != provider)
                    .anyMatch(other -> other.isEnabled() && registry.isImplemented(other.getProvider())
                            && !(other.getProvider() == PaymentProvider.MOCK && runtime.isProd()));
            if (!anotherAvailable) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Deshabilitá primero el master operativo de pagos o habilitá otro proveedor antes de quitar el último disponible");
            }
        }

        config.setDisplayName(displayName); config.setEnabled(input.enabled()); config.setMode(mode); config.setPriority(priority);
        config.setSubscriptionsEnabled(input.subscriptionsEnabled()); config.setPromotionsEnabled(input.promotionsEnabled());
        config.setSupportedCurrencies(currencies); config.setSupportedCountries(countries); config.setConfigurationJson(configJson);
        if (connectivityChanged) {
            config.setLastConnectivityCheckSuccess(null);
            config.setLastConnectivityCheckMessage("Configuración modificada; ejecutá nuevamente la prueba de conectividad");
        }
        config.setUpdatedByBackoffice(actor); providers.save(config);

        boolean enabledChanged = !Objects.equals(before.get("enabled"), config.isEnabled());
        AdminAuditAction action = enabledChanged
                ? (config.isEnabled() ? AdminAuditAction.ADMIN_ENABLE_PAYMENT_PROVIDER : AdminAuditAction.ADMIN_DISABLE_PAYMENT_PROVIDER)
                : AdminAuditAction.ADMIN_UPDATE_PAYMENT_PROVIDER_CONFIG;
        audit.record(actor, action, AdminAuditEntityType.PAYMENT_PROVIDER, provider.name(), before, PaymentProviderConfigWriter.snapshot(config), reason);
        return dto(config);
    }

    @Transactional
    public ProviderDTO putCredential(PaymentProvider provider, String rawName, String value, String rawReason, BackofficeUser actor) {
        provider = requireProvider(provider);
        if (provider == PaymentProvider.MOCK) throw bad("MOCK no utiliza credenciales");
        String reason = audit.requireReason(rawReason);
        String name = cleanCredentialName(rawName);
        if (value == null || value.isBlank()) throw bad("La credencial no puede estar vacía");
        if (value.length() > 10000) throw bad("La credencial es demasiado larga");
        PaymentProviderCredential existing = credentials.findByProviderAndCredentialName(provider, name).orElse(null);
        Map<String,Object> before = credentialSnapshot(existing);
        PaymentProviderCredential credential = existing == null ? PaymentProviderCredential.builder().provider(provider).credentialName(name).build() : existing;
        credential.setEncryptedValue(crypto.encrypt(value));
        credential.setFingerprint(crypto.fingerprint(value));
        credential.setMaskedSuffix(crypto.maskedSuffix(value));
        credential.setUpdatedByBackoffice(actor);
        credentials.save(credential);
        PaymentProviderConfig providerConfig = providers.findByProviderForUpdate(provider).orElseThrow();
        if (provider != PaymentProvider.MERCADO_PAGO
                || MercadoPagoPaymentGateway.credentialAffectsMode(name, providerConfig.getMode())) {
            providerConfig.setLastConnectivityCheckSuccess(null);
            providerConfig.setLastConnectivityCheckMessage("Credenciales del modo activo modificadas; ejecutá nuevamente la prueba de conectividad");
        }
        providers.save(providerConfig);
        audit.record(actor, AdminAuditAction.ADMIN_UPDATE_PAYMENT_PROVIDER_CREDENTIALS,
                AdminAuditEntityType.PAYMENT_PROVIDER, provider.name(), before, credentialSnapshot(credential), reason);
        return dto(runtime.provider(provider));
    }

    @Transactional
    public ProviderDTO removeCredential(PaymentProvider provider, String rawName, String rawReason, BackofficeUser actor) {
        provider = requireProvider(provider);
        String reason = audit.requireReason(rawReason);
        String name = cleanCredentialName(rawName);
        PaymentProviderCredential existing = credentials.findByProviderAndCredentialName(provider, name)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Credencial no encontrada"));
        Map<String,Object> before = credentialSnapshot(existing);
        credentials.delete(existing);
        PaymentProviderConfig providerConfig = providers.findByProviderForUpdate(provider).orElseThrow();
        if (provider != PaymentProvider.MERCADO_PAGO
                || MercadoPagoPaymentGateway.credentialAffectsMode(name, providerConfig.getMode())) {
            providerConfig.setLastConnectivityCheckSuccess(null);
            providerConfig.setLastConnectivityCheckMessage("Credencial del modo activo eliminada; ejecutá nuevamente la prueba de conectividad");
        }
        providers.save(providerConfig);
        audit.record(actor, AdminAuditAction.ADMIN_REMOVE_PAYMENT_PROVIDER_CREDENTIALS,
                AdminAuditEntityType.PAYMENT_PROVIDER, provider.name(), before,
                Map.of("credentialName", name, "removed", true), reason);
        return dto(runtime.provider(provider));
    }

    @Transactional
    public ProviderDTO testConnection(PaymentProvider provider, BackofficeUser actor) {
        PaymentProviderConfig config = runtime.provider(requireProvider(provider));
        return testConnection(provider, config.getMode(), actor, true);
    }

    @Transactional
    public ProviderDTO testConnection(PaymentProvider provider, PaymentProviderMode mode, BackofficeUser actor) {
        return testConnection(provider, mode, actor, false);
    }

    private ProviderDTO testConnection(PaymentProvider provider, PaymentProviderMode mode, BackofficeUser actor,
                                       boolean updateLegacyActiveModeCheck) {
        provider = requireProvider(provider);
        PaymentProviderConfig config = runtime.provider(provider);
        if (provider == PaymentProvider.MOCK && runtime.isProd()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "MOCK está prohibido en producción");
        }
        if (provider == PaymentProvider.MERCADO_PAGO) {
            if (mode == null || mode == PaymentProviderMode.TEST) throw bad("Mercado Pago solo admite SANDBOX o LIVE");
        } else if (mode != null && mode != config.getMode()) {
            throw bad("La prueba por ambiente separado solo está disponible para Mercado Pago");
        }

        PaymentGateway gateway;
        try { gateway = registry.requireImplemented(provider); }
        catch (IllegalStateException e) { throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage()); }
        PaymentGateway.ConnectionTestResult result;
        try {
            result = provider == PaymentProvider.MERCADO_PAGO
                    ? ((MercadoPagoPaymentGateway) gateway).testConnection(mode)
                    : gateway.testConnection();
        } catch (Exception e) {
            result = new PaymentGateway.ConnectionTestResult(false, safeMessage(e));
        }

        if (provider == PaymentProvider.MERCADO_PAGO) {
            String accessTokenName = MercadoPagoPaymentGateway.accessTokenCredentialName(mode);
            String fingerprint = credentials.findByProviderAndCredentialName(provider, accessTokenName)
                    .map(PaymentProviderCredential::getFingerprint).orElse(null);
            PaymentProviderEnvironmentCheck before = environmentChecks.findByProviderAndMode(provider, mode).orElse(null);
            PaymentProviderEnvironmentCheck check = before == null
                    ? PaymentProviderEnvironmentCheck.builder().provider(provider).mode(mode).build()
                    : before;
            Map<String,Object> beforeSnapshot = environmentCheckSnapshot(before);
            check.setCredentialFingerprint(fingerprint);
            check.setCheckedAt(OffsetDateTime.now(ZoneOffset.UTC));
            check.setSuccess(result.success());
            check.setMessage(trim(result.message(), 500));
            check.setCheckedByBackoffice(actor);
            environmentChecks.save(check);
            if (!(updateLegacyActiveModeCheck || mode == config.getMode())) {
                audit.record(actor, AdminAuditAction.ADMIN_TEST_PAYMENT_PROVIDER_CONNECTION,
                        AdminAuditEntityType.PAYMENT_PROVIDER, provider.name() + ":" + mode.name(),
                        beforeSnapshot, environmentCheckSnapshot(check), null);
            }
        }

        if (updateLegacyActiveModeCheck || mode == config.getMode()) {
            writer.recordConnectionTest(provider, result.success(), result.message(), actor);
        }
        return dto(runtime.provider(provider));
    }

    private ProviderDTO dto(PaymentProviderConfig p) {
        List<CredentialDTO> cs = credentials.findByProviderOrderByCredentialNameAsc(p.getProvider()).stream()
                .map(c -> new CredentialDTO(c.getCredentialName(), c.getFingerprint(), c.getMaskedSuffix(), c.getUpdatedAt())).toList();
        String status;
        if (!runtime.infrastructureAllowed()) status = "INFRA_BLOCKED";
        else if (p.getProvider()==PaymentProvider.MOCK && runtime.isProd()) status = "MOCK_FORBIDDEN_PROD";
        else if (!registry.isImplemented(p.getProvider())) status = "NOT_IMPLEMENTED";
        else if (!p.isEnabled()) status = "DISABLED";
        else if (Boolean.FALSE.equals(p.getLastConnectivityCheckSuccess())) status = "DEGRADED";
        else status = "READY";
        boolean available = runtime.infrastructureAllowed() && runtime.settings().isPaymentsEnabled() && p.isEnabled()
                && registry.isImplemented(p.getProvider()) && !(p.getProvider()==PaymentProvider.MOCK && runtime.isProd());
        return new ProviderDTO(p.getProvider(), p.getDisplayName(), p.isEnabled(), p.getMode(), p.getPriority(),
                p.isSubscriptionsEnabled(), p.isPromotionsEnabled(), split(p.getSupportedCurrencies()), split(p.getSupportedCountries()),
                p.getConfigurationJson(), registry.isImplemented(p.getProvider()), p.getProvider()==PaymentProvider.MOCK,
                available, status, p.getLastConnectivityCheckAt(), p.getLastConnectivityCheckSuccess(), p.getLastConnectivityCheckMessage(),
                p.getLastWebhookAt(), cs, environmentCheckDtos(p.getProvider()));
    }

    private List<EnvironmentCheckDTO> environmentCheckDtos(PaymentProvider provider) {
        if (provider != PaymentProvider.MERCADO_PAGO) return List.of();
        return List.of(environmentCheckDto(provider, PaymentProviderMode.SANDBOX),
                environmentCheckDto(provider, PaymentProviderMode.LIVE));
    }

    private EnvironmentCheckDTO environmentCheckDto(PaymentProvider provider, PaymentProviderMode mode) {
        String accessTokenName = MercadoPagoPaymentGateway.accessTokenCredentialName(mode);
        String webhookSecretName = MercadoPagoPaymentGateway.webhookSecretCredentialName(mode);
        PaymentProviderCredential accessToken = credentials.findByProviderAndCredentialName(provider, accessTokenName).orElse(null);
        boolean webhookConfigured = credentials.findByProviderAndCredentialName(provider, webhookSecretName).isPresent();
        PaymentProviderEnvironmentCheck check = environmentChecks.findByProviderAndMode(provider, mode).orElse(null);
        boolean credentialCurrent = check != null && accessToken != null && check.getCredentialFingerprint() != null
                && check.getCredentialFingerprint().equals(accessToken.getFingerprint());
        boolean verified = credentialCurrent && check.isSuccess();
        return new EnvironmentCheckDTO(mode, accessToken != null, webhookConfigured, verified, credentialCurrent,
                check == null ? null : check.getCheckedAt(), check == null ? null : check.isSuccess(),
                check == null ? null : check.getMessage());
    }

    private OperationalAlertEmailConfigDTO operationalAlerts(PaymentSettings s) {
        var state = alertNotifications.state();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        boolean running = state.leaseOwner() != null && state.leaseUntil() != null && state.leaseUntil().isAfter(now);
        PaymentAlertSeverity severity = s.getOperationalAlertMinSeverity() == null
                ? PaymentAlertSeverity.CRITICAL : s.getOperationalAlertMinSeverity();
        int cooldown = s.getOperationalAlertCooldownMinutes() <= 0 ? 180 : s.getOperationalAlertCooldownMinutes();
        return new OperationalAlertEmailConfigDTO(s.isOperationalAlertEmailEnabled(), alertEmailService.recipients(s),
                severity, cooldown, running, state.lastScanAt(), state.lastSuccessfulScanAt(), state.lastEmailAt(),
                state.lastError());
    }

    private Map<String,Object> operationalAlertSnapshot(PaymentSettings s) {
        Map<String,Object> m = new LinkedHashMap<>();
        m.put("emailEnabled", s.isOperationalAlertEmailEnabled());
        m.put("recipients", alertEmailService.recipients(s));
        m.put("minimumSeverity", s.getOperationalAlertMinSeverity());
        m.put("cooldownMinutes", s.getOperationalAlertCooldownMinutes());
        return m;
    }

    private List<String> normalizeEmails(List<String> values) {
        if (values == null || values.isEmpty()) return List.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) continue;
            String email = value.trim();
            if (email.length() > 254 || !email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) throw bad("Correo inválido: " + email);
            out.add(email);
        }
        if (out.size() > 20) throw bad("Se permiten hasta 20 destinatarios de alertas");
        return List.copyOf(out);
    }

    private void validateDefault(PaymentProvider provider) {
        PaymentProviderConfig config = runtime.provider(provider);
        if (!config.isEnabled()) throw new ResponseStatusException(HttpStatus.CONFLICT, "El proveedor predeterminado debe estar habilitado");
        if (!registry.isImplemented(provider)) throw new ResponseStatusException(HttpStatus.CONFLICT, "El proveedor predeterminado debe estar implementado");
        if (provider==PaymentProvider.MOCK && runtime.isProd()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "MOCK no puede ser predeterminado en producción");
    }

    private ReconciliationConfigDTO reconciliation(PaymentSettings s) {
        return new ReconciliationConfigDTO(s.isAutomaticReconciliationEnabled(), s.getReconciliationIntervalMinutes(),
                s.getReconciliationBatchSize(), s.getReconciliationLeaseOwner()!=null && s.getReconciliationLeaseUntil()!=null
                && s.getReconciliationLeaseUntil().isAfter(OffsetDateTime.now(ZoneOffset.UTC)),
                s.getReconciliationLastStartedAt(), s.getReconciliationLastCompletedAt(),
                s.getReconciliationLastSuccess(), s.getReconciliationLastMessage());
    }
    private Map<String,Object> reconciliationSnapshot(PaymentSettings s) {
        Map<String,Object> m = new LinkedHashMap<>();
        m.put("enabled", s.isAutomaticReconciliationEnabled());
        m.put("intervalMinutes", s.getReconciliationIntervalMinutes());
        m.put("batchSize", s.getReconciliationBatchSize());
        return m;
    }
    private Map<String,Object> settingsSnapshot(PaymentSettings s) {
        Map<String,Object> m = new LinkedHashMap<>(); m.put("paymentsEnabled", s.isPaymentsEnabled()); m.put("defaultProvider", s.getDefaultProvider()); return m;
    }
    private Map<String,Object> credentialSnapshot(PaymentProviderCredential c) {
        if (c == null) return null;
        Map<String,Object> m = new LinkedHashMap<>(); m.put("credentialName", c.getCredentialName()); m.put("fingerprint", c.getFingerprint()); m.put("maskedSuffix", c.getMaskedSuffix()); m.put("updatedAt", c.getUpdatedAt()); return m;
    }
    private String cleanCredentialName(String raw) { String x = raw==null?"":raw.trim().toUpperCase(Locale.ROOT); if(!CREDENTIAL_NAME.matcher(x).matches()) throw bad("Nombre de credencial inválido"); return x; }
    private PaymentProvider requireProvider(PaymentProvider p) { if (p==null) throw bad("Proveedor obligatorio"); return p; }
    private String cleanRequired(String v,String label,int max) { if(v==null||v.isBlank()) throw bad(label+" es obligatorio"); String x=v.trim(); if(x.length()>max) throw bad(label+" es demasiado largo"); return x; }
    private String normalizeCodes(List<String> values,int len,String label) { if(values==null||values.isEmpty()) return null; LinkedHashSet<String> out=new LinkedHashSet<>(); for(String v:values){if(v==null||v.isBlank())continue;String x=v.trim().toUpperCase(Locale.ROOT);if(x.length()!=len||!x.chars().allMatch(Character::isLetter))throw bad("Código de "+label+" inválido: "+x); if(len==3){try{Currency.getInstance(x);}catch(IllegalArgumentException e){throw bad("Moneda ISO inválida: "+x);}} else if(len==2 && !Set.of(Locale.getISOCountries()).contains(x)){throw bad("País ISO inválido: "+x);} out.add(x);} return out.isEmpty()?null:String.join(",",out); }
    private List<String> split(String value) { return value==null||value.isBlank()?List.of():Arrays.stream(value.split(",")).map(String::trim).filter(x->!x.isEmpty()).toList(); }
    private String normalizeJson(String value) { if(value==null||value.isBlank()) return null; String x=value.trim(); if(x.length()>10000) throw bad("La configuración JSON es demasiado larga"); try { JsonNode node=objectMapper.readTree(x); if(!node.isObject()) throw bad("configurationJson debe ser un objeto JSON"); rejectSecretLikeKeys(node); return objectMapper.writeValueAsString(node);} catch(ResponseStatusException e){throw e;} catch(Exception e){throw bad("configurationJson no contiene JSON válido");} }
    private void validateMercadoPagoConfiguration(PaymentProvider provider, PaymentProviderMode mode, String configJson) {
        if (provider != PaymentProvider.MERCADO_PAGO) return;
        String testPayerEmail = jsonText(configJson, "testPayerEmail");
        if (mode != PaymentProviderMode.LIVE && testPayerEmail == null) {
            throw bad("Mercado Pago SANDBOX requiere testPayerEmail de un comprador de prueba");
        }
        if (testPayerEmail != null && !testPayerEmail.toLowerCase(Locale.ROOT).endsWith("@testuser.com")) {
            throw bad("testPayerEmail debe pertenecer a un comprador de prueba de Mercado Pago terminado en @testuser.com");
        }
    }
    private String connectivityRelevantJson(PaymentProvider provider, PaymentProviderMode mode, String configJson) {
        if (provider != PaymentProvider.MERCADO_PAGO || mode != PaymentProviderMode.LIVE || configJson == null || configJson.isBlank()) {
            return configJson;
        }
        try {
            JsonNode node = objectMapper.readTree(configJson);
            if (node.isObject()) ((com.fasterxml.jackson.databind.node.ObjectNode) node).remove("testPayerEmail");
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            return configJson;
        }
    }
    private String jsonText(String configJson, String key) {
        if (configJson == null || configJson.isBlank()) return null;
        try {
            JsonNode value = objectMapper.readTree(configJson).get(key);
            return value == null || value.isNull() || !value.isTextual() || value.asText().isBlank() ? null : value.asText().trim();
        } catch (Exception e) {
            return null;
        }
    }
    private Map<String,Object> environmentCheckSnapshot(PaymentProviderEnvironmentCheck check) {
        if (check == null) return null;
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("provider", check.getProvider());
        result.put("mode", check.getMode());
        result.put("credentialFingerprint", check.getCredentialFingerprint());
        result.put("checkedAt", check.getCheckedAt());
        result.put("success", check.isSuccess());
        result.put("message", check.getMessage());
        return result;
    }

    private String trim(String value, int max) {
        if (value == null) return null;
        String x = value.trim();
        return x.length() <= max ? x : x.substring(0, max);
    }

    private void rejectSecretLikeKeys(JsonNode node) {
        if (node == null) return;
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                var entry = fields.next(); String k = entry.getKey().toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
                if (k.contains("secret") || k.contains("password") || k.contains("token") || k.contains("apikey") || k.contains("privatekey")) {
                    throw bad("La configuración JSON no puede contener secretos (" + entry.getKey() + "); usá Credenciales");
                }
                rejectSecretLikeKeys(entry.getValue());
            }
        } else if (node.isArray()) { for (JsonNode child : node) rejectSecretLikeKeys(child); }
    }
    private String safeMessage(Exception e) { String m=e.getMessage(); if(m==null||m.isBlank()) return "La prueba de conectividad falló"; return m.length()>500?m.substring(0,500):m; }
    private ResponseStatusException bad(String m) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,m); }

    public record SettingsDTO(boolean infrastructureAllowed, boolean databasePaymentsEnabled, boolean paymentsEnabled,
                              boolean production, boolean secretsStorageReady, PaymentProvider defaultProvider,
                              ReconciliationConfigDTO reconciliation, OperationalAlertEmailConfigDTO operationalAlerts,
                              List<ProviderDTO> providers) {}
    public record ReconciliationConfigDTO(boolean enabled, int intervalMinutes, int batchSize, boolean running,
                                          OffsetDateTime lastStartedAt, OffsetDateTime lastCompletedAt,
                                          Boolean lastSuccess, String lastMessage) {}
    public record OperationalAlertEmailConfigDTO(boolean enabled, List<String> recipients, PaymentAlertSeverity minimumSeverity,
                                                  int cooldownMinutes, boolean running, OffsetDateTime lastScanAt,
                                                  OffsetDateTime lastSuccessfulScanAt, OffsetDateTime lastEmailAt,
                                                  String lastError) {}
    public record AlertEmailTestDTO(boolean sent, String message) {}
    public record ProviderDTO(PaymentProvider provider, String displayName, boolean enabled, PaymentProviderMode mode,
                              int priority, boolean subscriptionsEnabled, boolean promotionsEnabled,
                              List<String> supportedCurrencies, List<String> supportedCountries, String configurationJson,
                              boolean implemented, boolean mock, boolean availableForNewPayments, String operationalStatus,
                              OffsetDateTime lastConnectivityCheckAt, Boolean lastConnectivityCheckSuccess,
                              String lastConnectivityCheckMessage, OffsetDateTime lastWebhookAt, List<CredentialDTO> credentials,
                              List<EnvironmentCheckDTO> environmentChecks) {}
    public record CredentialDTO(String name, String fingerprint, String maskedSuffix, OffsetDateTime updatedAt) {}
    public record EnvironmentCheckDTO(PaymentProviderMode mode, boolean accessTokenConfigured,
                                      boolean webhookSecretConfigured, boolean accessTokenVerified,
                                      boolean credentialCurrent, OffsetDateTime checkedAt, Boolean success, String message) {}
    public record ProviderUpdate(String displayName, boolean enabled, PaymentProviderMode mode, int priority,
                                 boolean subscriptionsEnabled, boolean promotionsEnabled, List<String> supportedCurrencies,
                                 List<String> supportedCountries, String configurationJson, String reason) {}
}
