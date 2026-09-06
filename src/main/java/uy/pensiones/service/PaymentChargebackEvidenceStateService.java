package uy.pensiones.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import uy.pensiones.enums.*;
import uy.pensiones.model.BackofficeUser;
import uy.pensiones.model.PaymentChargeback;
import uy.pensiones.repo.PaymentChargebackRepository;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class PaymentChargebackEvidenceStateService {
    private static final Duration STALE_IN_PROGRESS = Duration.ofMinutes(10);

    private final PaymentChargebackRepository chargebacks;
    private final AdminAuditService audit;
    private final ObjectMapper objectMapper;

    public PaymentChargebackEvidenceStateService(PaymentChargebackRepository chargebacks, AdminAuditService audit, ObjectMapper objectMapper) {
        this.chargebacks = chargebacks;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public UploadTarget begin(Long id, BackofficeUser actor, String reason, String manifestJson, int fileCount, long totalBytes) {
        String why = audit.requireReason(reason);
        PaymentChargeback c = lock(id);
        normalizeStaleInProgress(c);
        validateCanSubmit(c);
        c.setDocumentationSubmissionState(ChargebackDocumentationSubmissionState.IN_PROGRESS);
        c.setDocumentationSubmissionStartedAt(OffsetDateTime.now(ZoneOffset.UTC));
        c.setDocumentationSubmittedAt(null);
        c.setDocumentationSubmittedByBackoffice(actor);
        c.setDocumentationSubmissionReason(why);
        c.setDocumentationFileCount(fileCount);
        c.setDocumentationTotalBytes(totalBytes);
        c.setDocumentationManifestJson(manifestJson);
        chargebacks.save(c);
        return new UploadTarget(c.getProvider(), c.getProviderChargebackId(), c.getProviderPaymentId());
    }

    @Transactional
    public void complete(Long id) {
        PaymentChargeback c = lock(id);
        if (c.getDocumentationSubmissionState() == ChargebackDocumentationSubmissionState.SUBMITTED) return;
        if (c.getDocumentationSubmissionState() != ChargebackDocumentationSubmissionState.IN_PROGRESS
                && c.getDocumentationSubmissionState() != ChargebackDocumentationSubmissionState.UNKNOWN) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "No existe una carga de evidencia pendiente de confirmar");
        }
        Map<String,Object> before = snapshot(c);
        c.setDocumentationSubmissionState(ChargebackDocumentationSubmissionState.SUBMITTED);
        c.setDocumentationSubmittedAt(OffsetDateTime.now(ZoneOffset.UTC));
        if (c.getDocumentationStatus() == null || c.getDocumentationStatus().isBlank() || "not_supplied".equalsIgnoreCase(c.getDocumentationStatus())) {
            c.setDocumentationStatus("review_pending");
        }
        c = chargebacks.save(c);
        BackofficeUser actor = c.getDocumentationSubmittedByBackoffice();
        if (actor == null) throw new IllegalStateException("La carga de evidencia no tiene actor administrativo asociado");
        audit.record(actor, AdminAuditAction.ADMIN_SUBMIT_CHARGEBACK_DOCUMENTATION, AdminAuditEntityType.PAYMENT_CHARGEBACK,
                c.getId(), before, evidenceAuditAfter(c), c.getDocumentationSubmissionReason());
    }

    @Transactional
    public Resolution resolveAfterProviderRefresh(Long id) {
        PaymentChargeback c = lock(id);
        ChargebackDocumentationSubmissionState state = c.getDocumentationSubmissionState();
        if (state == null || state == ChargebackDocumentationSubmissionState.SUBMITTED) return Resolution.UNCHANGED;
        normalizeStaleInProgress(c);
        state = c.getDocumentationSubmissionState();
        if (providerShowsSubmitted(c.getDocumentationStatus())) {
            completeLocked(c);
            return Resolution.CONFIRMED_SUBMITTED;
        }
        if (state == ChargebackDocumentationSubmissionState.IN_PROGRESS) return Resolution.UNCHANGED;
        if (state == ChargebackDocumentationSubmissionState.UNKNOWN && providerShowsNotSupplied(c.getDocumentationStatus())) {
            clearSubmission(c);
            chargebacks.save(c);
            return Resolution.CONFIRMED_NOT_SUBMITTED;
        }
        return Resolution.UNCHANGED;
    }

    @Transactional
    public void markUnknown(Long id) {
        PaymentChargeback c = lock(id);
        if (c.getDocumentationSubmissionState() == ChargebackDocumentationSubmissionState.IN_PROGRESS) {
            c.setDocumentationSubmissionState(ChargebackDocumentationSubmissionState.UNKNOWN);
            chargebacks.save(c);
        }
    }

    @Transactional
    public void clearConfirmedFailure(Long id) {
        PaymentChargeback c = lock(id);
        if (providerShowsNotSupplied(c.getDocumentationStatus())) {
            clearSubmission(c);
            chargebacks.save(c);
        }
    }

    private void completeLocked(PaymentChargeback c) {
        if (c.getDocumentationSubmissionState() == ChargebackDocumentationSubmissionState.SUBMITTED) return;
        Map<String,Object> before = snapshot(c);
        c.setDocumentationSubmissionState(ChargebackDocumentationSubmissionState.SUBMITTED);
        c.setDocumentationSubmittedAt(OffsetDateTime.now(ZoneOffset.UTC));
        c = chargebacks.save(c);
        BackofficeUser actor = c.getDocumentationSubmittedByBackoffice();
        if (actor != null) {
            audit.record(actor, AdminAuditAction.ADMIN_SUBMIT_CHARGEBACK_DOCUMENTATION, AdminAuditEntityType.PAYMENT_CHARGEBACK,
                    c.getId(), before, evidenceAuditAfter(c), c.getDocumentationSubmissionReason());
        }
    }

    private void validateCanSubmit(PaymentChargeback c) {
        if (c.getStatus() != PaymentChargebackStatus.OPEN) throw conflict("Solo se puede aportar evidencia mientras el contracargo está abierto");
        if (!Boolean.TRUE.equals(c.getCoverageEligible())) throw conflict("Mercado Pago no marcó este contracargo como elegible para cobertura");
        if (!Boolean.TRUE.equals(c.getDocumentationRequired())) throw conflict("Mercado Pago no requiere documentación para este contracargo");
        if (c.getDocumentationDeadline() == null || !c.getDocumentationDeadline().isAfter(OffsetDateTime.now(ZoneOffset.UTC))) {
            throw conflict("El plazo para presentar documentación ya venció o no está disponible");
        }
        if (!providerShowsNotSupplied(c.getDocumentationStatus())) {
            throw conflict("Mercado Pago ya recibió documentación o el estado actual no permite una nueva carga");
        }
        if (c.getDocumentationSubmissionState() == ChargebackDocumentationSubmissionState.SUBMITTED) throw conflict("La evidencia ya fue enviada");
        if (c.getDocumentationSubmissionState() == ChargebackDocumentationSubmissionState.UNKNOWN) {
            throw conflict("El resultado de la carga anterior es incierto. Sincronizá el contracargo antes de reintentar");
        }
        if (c.getDocumentationSubmissionState() == ChargebackDocumentationSubmissionState.IN_PROGRESS) {
            throw conflict("Ya existe una carga de evidencia en curso");
        }
    }

    private void normalizeStaleInProgress(PaymentChargeback c) {
        if (c.getDocumentationSubmissionState() != ChargebackDocumentationSubmissionState.IN_PROGRESS) return;
        OffsetDateTime started = c.getDocumentationSubmissionStartedAt();
        if (started != null && started.plus(STALE_IN_PROGRESS).isBefore(OffsetDateTime.now(ZoneOffset.UTC))) {
            c.setDocumentationSubmissionState(ChargebackDocumentationSubmissionState.UNKNOWN);
            chargebacks.save(c);
        }
    }

    private boolean providerShowsSubmitted(String status) {
        return status != null && !status.isBlank() && !providerShowsNotSupplied(status);
    }

    private boolean providerShowsNotSupplied(String status) {
        return status != null && "not_supplied".equalsIgnoreCase(status.trim());
    }

    private void clearSubmission(PaymentChargeback c) {
        c.setDocumentationSubmissionState(null);
        c.setDocumentationSubmissionStartedAt(null);
        c.setDocumentationSubmittedAt(null);
        c.setDocumentationSubmittedByBackoffice(null);
        c.setDocumentationSubmissionReason(null);
        c.setDocumentationFileCount(null);
        c.setDocumentationTotalBytes(null);
        c.setDocumentationManifestJson(null);
    }

    private PaymentChargeback lock(Long id) {
        if (id == null || id <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Identificador inválido");
        return chargebacks.findByIdForUpdate(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Contracargo no encontrado"));
    }

    private Map<String,Object> snapshot(PaymentChargeback c) {
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("documentationStatus", c.getDocumentationStatus());
        out.put("submissionState", c.getDocumentationSubmissionState());
        out.put("submittedAt", c.getDocumentationSubmittedAt());
        return out;
    }

    private Map<String,Object> evidenceAuditAfter(PaymentChargeback c) {
        Map<String,Object> out = snapshot(c);
        out.put("fileCount", c.getDocumentationFileCount());
        out.put("totalBytes", c.getDocumentationTotalBytes());
        if (c.getDocumentationManifestJson() != null) {
            try { out.put("files", objectMapper.readTree(c.getDocumentationManifestJson())); }
            catch (Exception e) { out.put("files", "metadata_unavailable"); }
        }
        return out;
    }

    private ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }

    public enum Resolution { UNCHANGED, CONFIRMED_SUBMITTED, CONFIRMED_NOT_SUBMITTED }
    public record UploadTarget(PaymentProvider provider, String providerChargebackId, String providerPaymentId) {}
}
