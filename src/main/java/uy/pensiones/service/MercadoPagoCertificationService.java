package uy.pensiones.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import uy.pensiones.enums.*;
import uy.pensiones.model.*;
import uy.pensiones.payment.PaymentRuntimeConfigurationService;
import uy.pensiones.repo.*;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

@Service
public class MercadoPagoCertificationService {
    private static final PaymentProvider PROVIDER = PaymentProvider.MERCADO_PAGO;
    private static final EnumSet<PaymentProviderCertificationCheckCode> PRE_LIVE_MANUAL = EnumSet.of(
            PaymentProviderCertificationCheckCode.SANDBOX_ORDER_WEBHOOK_CONFIGURED,
            PaymentProviderCertificationCheckCode.SANDBOX_CHARGEBACK_WEBHOOK_CONFIGURED,
            PaymentProviderCertificationCheckCode.PRODUCTION_CREDENTIALS_ACTIVATED);
    private static final EnumSet<PaymentProviderCertificationCheckCode> LIVE_MANUAL = EnumSet.of(
            PaymentProviderCertificationCheckCode.PRODUCTION_ORDER_WEBHOOK_CONFIGURED,
            PaymentProviderCertificationCheckCode.PRODUCTION_CHARGEBACK_WEBHOOK_CONFIGURED);

    private final PaymentProviderCertificationRunRepository runs;
    private final PaymentProviderCertificationCheckRepository checks;
    private final PaymentProviderConfigRepository providers;
    private final PaymentRepository payments;
    private final PaymentProviderEventRepository events;
    private final PaymentRefundRepository refunds;
    private final PaymentChargebackRepository chargebacks;
    private final PaymentRuntimeConfigurationService runtime;
    private final MercadoPagoReadinessService readiness;
    private final AdminAuditService audit;
    private final AdminPaymentService adminPayments;
    private final ObjectMapper objectMapper;

    public MercadoPagoCertificationService(PaymentProviderCertificationRunRepository runs,
                                            PaymentProviderCertificationCheckRepository checks,
                                            PaymentProviderConfigRepository providers,
                                            PaymentRepository payments,
                                            PaymentProviderEventRepository events,
                                            PaymentRefundRepository refunds,
                                            PaymentChargebackRepository chargebacks,
                                            PaymentRuntimeConfigurationService runtime,
                                            MercadoPagoReadinessService readiness,
                                            AdminAuditService audit,
                                            AdminPaymentService adminPayments,
                                            ObjectMapper objectMapper) {
        this.runs = runs;
        this.checks = checks;
        this.providers = providers;
        this.payments = payments;
        this.events = events;
        this.refunds = refunds;
        this.chargebacks = chargebacks;
        this.runtime = runtime;
        this.readiness = readiness;
        this.audit = audit;
        this.adminPayments = adminPayments;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public CertificationDTO get() {
        PaymentProviderCertificationRun run = runs.findFirstByProviderAndStatusOrderByStartedAtDesc(
                PROVIDER, PaymentProviderCertificationStatus.ACTIVE)
                .orElseGet(() -> runs.findFirstByProviderOrderByStartedAtDesc(PROVIDER).orElse(null));
        return dto(run);
    }

    @Transactional
    public CertificationDTO start(String rawReason, BackofficeUser actor) {
        String reason = audit.requireReason(rawReason);
        if (runs.findFirstByProviderAndStatusOrderByStartedAtDesc(PROVIDER, PaymentProviderCertificationStatus.ACTIVE).isPresent()) {
            throw conflict("Ya existe una certificación activa de Mercado Pago");
        }
        PaymentProviderMode mode = provider().getMode();
        if (mode == PaymentProviderMode.LIVE) {
            throw conflict("La certificación Sandbox → LIVE debe iniciarse antes de cambiar Mercado Pago a LIVE");
        }
        PaymentProviderCertificationRun run = runs.save(PaymentProviderCertificationRun.builder()
                .provider(PROVIDER)
                .status(PaymentProviderCertificationStatus.ACTIVE)
                .startedMode(mode)
                .startedByBackoffice(actor)
                .build());
        for (PaymentProviderCertificationCheckCode code : PaymentProviderCertificationCheckCode.values()) {
            checks.save(PaymentProviderCertificationCheck.builder().run(run).checkCode(code).confirmed(false).build());
        }
        audit.record(actor, AdminAuditAction.ADMIN_START_PAYMENT_PROVIDER_CERTIFICATION,
                AdminAuditEntityType.PAYMENT_PROVIDER_CERTIFICATION, run.getId(), null, runSnapshot(run), reason);
        return dto(run);
    }

    @Transactional
    public CertificationDTO setManualCheck(Long runId, PaymentProviderCertificationCheckCode code,
                                            boolean confirmed, String rawReason, BackofficeUser actor) {
        String reason = audit.requireReason(rawReason);
        PaymentProviderCertificationRun run = activeRun(runId);
        PaymentProviderMode currentMode = provider().getMode();
        if (PRE_LIVE_MANUAL.contains(code) && currentMode == PaymentProviderMode.LIVE) {
            throw conflict("Ese control debe quedar confirmado antes de cambiar Mercado Pago a LIVE");
        }
        if (LIVE_MANUAL.contains(code) && currentMode != PaymentProviderMode.LIVE) {
            throw conflict("Ese control corresponde al modo productivo y solo puede confirmarse con Mercado Pago en LIVE");
        }
        PaymentProviderCertificationCheck check = checks.findForUpdate(run.getId(), code)
                .orElseGet(() -> PaymentProviderCertificationCheck.builder().run(run).checkCode(code).build());
        Map<String, Object> before = checkSnapshot(check);
        check.setConfirmed(confirmed);
        check.setReason(reason);
        if (confirmed) {
            check.setConfirmedByBackoffice(actor);
            check.setConfirmedAt(OffsetDateTime.now(ZoneOffset.UTC));
        } else {
            check.setConfirmedByBackoffice(null);
            check.setConfirmedAt(null);
            if (PRE_LIVE_MANUAL.contains(code)) invalidatePreLive(run);
        }
        checks.save(check);
        audit.record(actor,
                confirmed ? AdminAuditAction.ADMIN_CONFIRM_PAYMENT_PROVIDER_CERTIFICATION_CHECK
                        : AdminAuditAction.ADMIN_REVOKE_PAYMENT_PROVIDER_CERTIFICATION_CHECK,
                AdminAuditEntityType.PAYMENT_PROVIDER_CERTIFICATION, run.getId(), before, checkSnapshot(check), reason);
        return dto(run);
    }

    @Transactional
    public CertificationDTO validatePreLive(Long runId, String rawReason, BackofficeUser actor) {
        String reason = audit.requireReason(rawReason);
        PaymentProviderCertificationRun run = activeRun(runId);
        PaymentProviderMode currentMode = provider().getMode();
        if (currentMode == PaymentProviderMode.LIVE) {
            throw conflict("Mercado Pago ya está en LIVE; la validación previa debe ejecutarse antes del cambio");
        }
        if (currentMode != run.getStartedMode()) {
            throw conflict("El modo cambió desde el inicio de la certificación. Abortá esta ejecución e iniciá una nueva");
        }
        Evaluation eval = evaluate(run);
        if (!eval.preLiveBlockers().isEmpty()) {
            throw conflict("No se puede autorizar el cambio a LIVE: " + String.join(" · ", eval.preLiveBlockers()));
        }
        Map<String, Object> before = runSnapshot(run);
        run.setPreLiveValidatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        run.setPreLiveValidatedByBackoffice(actor);
        run.setPreLiveSnapshotJson(json(Map.of(
                "sandboxEvidence", eval.sandboxEvidence(),
                "manualChecks", manualCheckMap(run.getId()),
                "paymentsDisabledBeforeSwitch", true,
                "validatedAt", run.getPreLiveValidatedAt())));
        runs.save(run);
        audit.record(actor, AdminAuditAction.ADMIN_VALIDATE_PAYMENT_PROVIDER_PRE_LIVE,
                AdminAuditEntityType.PAYMENT_PROVIDER_CERTIFICATION, run.getId(), before, runSnapshot(run), reason);
        return dto(run);
    }

    public AdminPaymentService.PaymentDetailDTO createLiveSmokeCheckout(Long runId, PaymentTransactionService.CreateInput input,
                                                                            String rawReason, BackofficeUser actor) {
        String reason = audit.requireReason(rawReason);
        PaymentProviderCertificationRun run = runs.findById(runId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Certificación no encontrada"));
        if (run.getProvider() != PROVIDER || run.getStatus() != PaymentProviderCertificationStatus.ACTIVE) {
            throw conflict("La certificación no está activa");
        }
        if (run.getPreLiveValidatedAt() == null) {
            throw conflict("Primero debe quedar registrada la validación pre-LIVE");
        }
        if (provider().getMode() != PaymentProviderMode.LIVE) {
            throw conflict("Mercado Pago debe estar en LIVE para ejecutar el smoke test productivo");
        }
        if (runtime.settings().isPaymentsEnabled()) {
            throw conflict("El master global debe permanecer deshabilitado durante el smoke test LIVE controlado");
        }
        if (!readiness.get().liveConfigurationReady()) {
            throw conflict("La configuración LIVE todavía tiene controles automáticos bloqueantes");
        }
        return adminPayments.createCertificationLiveCheckout(input, reason, actor);
    }

    @Transactional
    public CertificationDTO complete(Long runId, String rawReason, BackofficeUser actor) {
        String reason = audit.requireReason(rawReason);
        PaymentProviderCertificationRun run = activeRun(runId);
        Evaluation eval = evaluate(run);
        if (!eval.completionBlockers().isEmpty()) {
            throw conflict("La certificación productiva todavía tiene bloqueos: " + String.join(" · ", eval.completionBlockers()));
        }
        Map<String, Object> before = runSnapshot(run);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        run.setStatus(PaymentProviderCertificationStatus.CERTIFIED);
        run.setCompletedAt(now);
        run.setCompletionSnapshotJson(json(Map.of(
                "sandboxEvidence", eval.sandboxEvidence(),
                "liveEvidence", eval.liveEvidence(),
                "manualChecks", manualCheckMap(run.getId()),
                "readiness", eval.readiness(),
                "certifiedAt", now)));
        runs.save(run);
        audit.record(actor, AdminAuditAction.ADMIN_CERTIFY_PAYMENT_PROVIDER_LIVE,
                AdminAuditEntityType.PAYMENT_PROVIDER_CERTIFICATION, run.getId(), before, runSnapshot(run), reason);
        return dto(run);
    }

    @Transactional
    public CertificationDTO abort(Long runId, String rawReason, BackofficeUser actor) {
        String reason = audit.requireReason(rawReason);
        PaymentProviderCertificationRun run = activeRun(runId);
        Map<String, Object> before = runSnapshot(run);
        run.setStatus(PaymentProviderCertificationStatus.ABORTED);
        run.setAbortedAt(OffsetDateTime.now(ZoneOffset.UTC));
        run.setAbortReason(reason);
        runs.save(run);
        audit.record(actor, AdminAuditAction.ADMIN_ABORT_PAYMENT_PROVIDER_CERTIFICATION,
                AdminAuditEntityType.PAYMENT_PROVIDER_CERTIFICATION, run.getId(), before, runSnapshot(run), reason);
        return dto(run);
    }

    private CertificationDTO dto(PaymentProviderCertificationRun run) {
        PaymentProviderMode currentMode = provider().getMode();
        MercadoPagoReadinessService.ReadinessDTO ready = readiness.get();
        if (run == null) {
            return new CertificationDTO(null, null, "NOT_STARTED", currentMode != PaymentProviderMode.LIVE,
                    false, false, currentMode, null, null, null, null, null, null, null,
                    List.of(), List.of(), List.of(), null, new EvidenceDTO(0, 0, 0, 0, 0), ready);
        }
        Evaluation eval = evaluate(run, ready);
        String phase = phase(run, currentMode, eval);
        List<ManualCheckDTO> manual = manualChecks(run.getId());
        return new CertificationDTO(run.getId(), run.getStatus(), phase,
                run.getStatus() != PaymentProviderCertificationStatus.ACTIVE && currentMode != PaymentProviderMode.LIVE,
                run.getStatus() == PaymentProviderCertificationStatus.ACTIVE && eval.preLiveBlockers().isEmpty()
                        && currentMode != PaymentProviderMode.LIVE,
                run.getStatus() == PaymentProviderCertificationStatus.ACTIVE && eval.completionBlockers().isEmpty(),
                currentMode, run.getStartedMode(), run.getStartedAt(), userName(run.getStartedByBackoffice()),
                run.getPreLiveValidatedAt(), userName(run.getPreLiveValidatedByBackoffice()), run.getCompletedAt(),
                run.getAbortedAt(), manual, eval.preLiveBlockers(), eval.completionBlockers(),
                eval.sandboxEvidence(), eval.liveEvidence(), ready);
    }

    private Evaluation evaluate(PaymentProviderCertificationRun run) {
        return evaluate(run, readiness.get());
    }

    private Evaluation evaluate(PaymentProviderCertificationRun run, MercadoPagoReadinessService.ReadinessDTO ready) {
        EvidenceDTO sandbox = sandboxEvidence(run);
        EvidenceDTO live = liveEvidence(run);
        Map<PaymentProviderCertificationCheckCode, Boolean> manual = manualCheckMap(run.getId());
        PaymentProviderMode currentMode = provider().getMode();
        PaymentSettings global = runtime.settings();

        List<String> pre = new ArrayList<>();
        if (currentMode == PaymentProviderMode.LIVE) pre.add("Mercado Pago debe seguir en TEST/SANDBOX durante la validación previa");
        if (currentMode != run.getStartedMode()) pre.add("El modo actual debe coincidir con el modo con el que se inició la certificación");
        if (global.isPaymentsEnabled()) pre.add("Deshabilitá pagos globalmente antes de autorizar el cambio de credenciales/modo");
        if (sandbox.checkouts() <= 0) pre.add("Falta crear un checkout de prueba en el modo inicial");
        if (sandbox.webhooks() <= 0) pre.add("Falta observar un webhook de prueba procesado");
        if (sandbox.approvedFulfilledPayments() <= 0) pre.add("Falta un pago de prueba aprobado con fulfillment aplicado");
        for (PaymentProviderCertificationCheckCode code : PRE_LIVE_MANUAL) {
            if (!Boolean.TRUE.equals(manual.get(code))) pre.add("Falta confirmar: " + label(code));
        }

        List<String> completion = new ArrayList<>();
        if (run.getPreLiveValidatedAt() == null) completion.add("La validación previa al cambio LIVE no fue registrada");
        if (currentMode != PaymentProviderMode.LIVE) completion.add("Mercado Pago todavía no está en modo LIVE");
        if (!ready.liveConfigurationReady()) completion.add("La configuración productiva automática todavía tiene controles bloqueantes");
        if (live.checkouts() <= 0) completion.add("Falta un checkout creado realmente en LIVE");
        if (live.webhooks() <= 0) completion.add("Falta un webhook live_mode=true procesado");
        if (live.approvedFulfilledPayments() <= 0) completion.add("Falta un pago real aprobado con fulfillment aplicado");
        for (PaymentProviderCertificationCheckCode code : LIVE_MANUAL) {
            if (!Boolean.TRUE.equals(manual.get(code))) completion.add("Falta confirmar: " + label(code));
        }
        return new Evaluation(List.copyOf(pre), List.copyOf(completion), sandbox, live, ready);
    }

    private EvidenceDTO sandboxEvidence(PaymentProviderCertificationRun run) {
        PaymentProviderMode mode = run.getStartedMode();
        OffsetDateTime since = run.getStartedAt();
        if (mode == null || mode == PaymentProviderMode.LIVE || since == null) return new EvidenceDTO(0, 0, 0, 0, 0);
        return new EvidenceDTO(
                payments.countByProviderAndProviderModeAndProviderCheckoutIdIsNotNullAndCreatedAtGreaterThanEqual(PROVIDER, mode, since),
                events.countByProviderAndProcessedAtIsNotNullAndLiveModeAndCreatedAtGreaterThanEqual(PROVIDER, false, since),
                payments.countByProviderAndProviderModeAndStatusAndFulfilledAtIsNotNullAndCreatedAtGreaterThanEqual(PROVIDER, mode, PaymentStatus.APPROVED, since),
                refunds.countByPayment_ProviderAndPayment_ProviderModeAndStatusAndCreatedAtGreaterThanEqual(PROVIDER, mode, PaymentRefundStatus.ACCEPTED, since),
                chargebacks.countByProviderAndLiveModeAndCreatedAtGreaterThanEqual(PROVIDER, false, since));
    }

    private EvidenceDTO liveEvidence(PaymentProviderCertificationRun run) {
        OffsetDateTime since = run.getPreLiveValidatedAt();
        if (since == null) return new EvidenceDTO(0, 0, 0, 0, 0);
        return new EvidenceDTO(
                payments.countByProviderAndProviderModeAndProviderCheckoutIdIsNotNullAndCreatedAtGreaterThanEqual(PROVIDER, PaymentProviderMode.LIVE, since),
                events.countByProviderAndProcessedAtIsNotNullAndLiveModeAndCreatedAtGreaterThanEqual(PROVIDER, true, since),
                payments.countByProviderAndProviderModeAndStatusAndFulfilledAtIsNotNullAndCreatedAtGreaterThanEqual(PROVIDER, PaymentProviderMode.LIVE, PaymentStatus.APPROVED, since),
                refunds.countByPayment_ProviderAndPayment_ProviderModeAndStatusAndCreatedAtGreaterThanEqual(PROVIDER, PaymentProviderMode.LIVE, PaymentRefundStatus.ACCEPTED, since),
                chargebacks.countByProviderAndLiveModeAndCreatedAtGreaterThanEqual(PROVIDER, true, since));
    }

    private String phase(PaymentProviderCertificationRun run, PaymentProviderMode currentMode, Evaluation eval) {
        if (run.getStatus() == PaymentProviderCertificationStatus.CERTIFIED) return "CERTIFIED";
        if (run.getStatus() == PaymentProviderCertificationStatus.ABORTED) return "ABORTED";
        if (currentMode != PaymentProviderMode.LIVE) {
            return run.getPreLiveValidatedAt() != null && currentMode == run.getStartedMode()
                    ? "READY_FOR_LIVE_SWITCH" : "SANDBOX_VALIDATION";
        }
        if (run.getPreLiveValidatedAt() == null) return "LIVE_SWITCH_NOT_AUTHORIZED";
        return eval.completionBlockers().isEmpty() ? "READY_TO_CERTIFY" : "LIVE_VALIDATION";
    }

    private List<ManualCheckDTO> manualChecks(Long runId) {
        Map<PaymentProviderCertificationCheckCode, PaymentProviderCertificationCheck> byCode = new EnumMap<>(PaymentProviderCertificationCheckCode.class);
        for (PaymentProviderCertificationCheck check : checks.findByRun_IdOrderByCheckCodeAsc(runId)) byCode.put(check.getCheckCode(), check);
        List<ManualCheckDTO> out = new ArrayList<>();
        for (PaymentProviderCertificationCheckCode code : PaymentProviderCertificationCheckCode.values()) {
            PaymentProviderCertificationCheck check = byCode.get(code);
            out.add(new ManualCheckDTO(code, label(code), stage(code), check != null && check.isConfirmed(),
                    check == null ? null : check.getConfirmedAt(), check == null ? null : userName(check.getConfirmedByBackoffice()),
                    check == null ? null : check.getReason()));
        }
        return List.copyOf(out);
    }

    private Map<PaymentProviderCertificationCheckCode, Boolean> manualCheckMap(Long runId) {
        Map<PaymentProviderCertificationCheckCode, Boolean> result = new EnumMap<>(PaymentProviderCertificationCheckCode.class);
        for (PaymentProviderCertificationCheckCode code : PaymentProviderCertificationCheckCode.values()) result.put(code, false);
        for (PaymentProviderCertificationCheck check : checks.findByRun_IdOrderByCheckCodeAsc(runId)) {
            result.put(check.getCheckCode(), check.isConfirmed());
        }
        return result;
    }

    private String label(PaymentProviderCertificationCheckCode code) {
        return switch (code) {
            case SANDBOX_ORDER_WEBHOOK_CONFIGURED -> "Webhook de prueba · Order (Mercado Pago)";
            case SANDBOX_CHARGEBACK_WEBHOOK_CONFIGURED -> "Webhook de prueba · Contracargos";
            case PRODUCTION_CREDENTIALS_ACTIVATED -> "Credenciales productivas activadas en Mercado Pago";
            case PRODUCTION_ORDER_WEBHOOK_CONFIGURED -> "Webhook productivo · Order (Mercado Pago)";
            case PRODUCTION_CHARGEBACK_WEBHOOK_CONFIGURED -> "Webhook productivo · Contracargos";
        };
    }

    private String stage(PaymentProviderCertificationCheckCode code) {
        return LIVE_MANUAL.contains(code) ? "LIVE" : "PRE_LIVE";
    }

    private PaymentProviderCertificationRun activeRun(Long id) {
        PaymentProviderCertificationRun run = runs.findByIdForUpdate(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Certificación no encontrada"));
        if (run.getProvider() != PROVIDER) throw conflict("La certificación no corresponde a Mercado Pago");
        if (run.getStatus() != PaymentProviderCertificationStatus.ACTIVE) throw conflict("La certificación ya no está activa");
        return run;
    }

    private PaymentProviderConfig provider() {
        return providers.findById(PROVIDER)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Falta configuración de MERCADO_PAGO"));
    }

    private void invalidatePreLive(PaymentProviderCertificationRun run) {
        run.setPreLiveValidatedAt(null);
        run.setPreLiveValidatedByBackoffice(null);
        run.setPreLiveSnapshotJson(null);
        runs.save(run);
    }

    private Map<String, Object> runSnapshot(PaymentProviderCertificationRun run) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", run.getId());
        m.put("provider", run.getProvider());
        m.put("status", run.getStatus());
        m.put("startedMode", run.getStartedMode());
        m.put("startedAt", run.getStartedAt());
        m.put("preLiveValidatedAt", run.getPreLiveValidatedAt());
        m.put("completedAt", run.getCompletedAt());
        m.put("abortedAt", run.getAbortedAt());
        return m;
    }

    private Map<String, Object> checkSnapshot(PaymentProviderCertificationCheck check) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", check.getCheckCode());
        m.put("confirmed", check.isConfirmed());
        m.put("confirmedAt", check.getConfirmedAt());
        m.put("confirmedBy", userName(check.getConfirmedByBackoffice()));
        return m;
    }

    private String userName(BackofficeUser user) {
        if (user == null) return null;
        return user.getDisplayName() == null || user.getDisplayName().isBlank() ? user.getUsername() : user.getDisplayName();
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("No se pudo persistir la evidencia de certificación", e); }
    }

    private ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }

    private record Evaluation(List<String> preLiveBlockers, List<String> completionBlockers,
                              EvidenceDTO sandboxEvidence, EvidenceDTO liveEvidence,
                              MercadoPagoReadinessService.ReadinessDTO readiness) {}

    public record CertificationDTO(Long id, PaymentProviderCertificationStatus status, String phase,
                                   boolean canStart, boolean canValidatePreLive, boolean canComplete,
                                   PaymentProviderMode currentMode, PaymentProviderMode startedMode,
                                   OffsetDateTime startedAt, String startedBy,
                                   OffsetDateTime preLiveValidatedAt, String preLiveValidatedBy,
                                   OffsetDateTime completedAt, OffsetDateTime abortedAt,
                                   List<ManualCheckDTO> manualChecks,
                                   List<String> preLiveBlockers, List<String> completionBlockers,
                                   EvidenceDTO sandboxEvidence, EvidenceDTO liveEvidence,
                                   MercadoPagoReadinessService.ReadinessDTO readiness) {}

    public record ManualCheckDTO(PaymentProviderCertificationCheckCode code, String label, String stage,
                                 boolean confirmed, OffsetDateTime confirmedAt, String confirmedBy, String reason) {}

    public record EvidenceDTO(long checkouts, long webhooks, long approvedFulfilledPayments,
                              long acceptedRefunds, long chargebacks) {}
}
