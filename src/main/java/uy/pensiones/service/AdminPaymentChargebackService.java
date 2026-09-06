package uy.pensiones.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import uy.pensiones.model.BackofficeUser;
import uy.pensiones.payment.PaymentGateway;
import uy.pensiones.payment.PaymentGatewayRegistry;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class AdminPaymentChargebackService {
    private static final long MAX_TOTAL_BYTES = 10L * 1024L * 1024L;
    private static final int MAX_FILES = 10;

    private final PaymentChargebackService chargebacks;
    private final PaymentChargebackEvidenceStateService evidenceState;
    private final PaymentGatewayRegistry gateways;
    private final ObjectMapper objectMapper;

    public AdminPaymentChargebackService(PaymentChargebackService chargebacks,
                                         PaymentChargebackEvidenceStateService evidenceState,
                                         PaymentGatewayRegistry gateways,
                                         ObjectMapper objectMapper) {
        this.chargebacks=chargebacks; this.evidenceState=evidenceState; this.gateways=gateways; this.objectMapper=objectMapper;
    }

    public PaymentChargebackService.ChargebackDTO refresh(Long id, BackofficeUser actor) {
        PaymentChargebackService.RefreshTarget target=chargebacks.refreshTarget(id);
        PaymentGateway gateway=gateways.requireImplemented(target.provider());
        PaymentGateway.PaymentDisputeResult result=gateway.getDispute(new PaymentGateway.PaymentDisputeLookupRequest(target.providerChargebackId(),target.providerPaymentId()));
        var synced=chargebacks.sync(target.provider(),result);
        evidenceState.resolveAfterProviderRefresh(synced.getId());
        return chargebacks.detail(synced.getId());
    }

    public PaymentChargebackService.ChargebackDTO submitDocumentation(Long id, List<MultipartFile> files, String reason, BackofficeUser actor) {
        EvidenceBundle evidence=readEvidence(files);

        // Refrescamos antes de comenzar para validar deadline/eligibilidad/status contra el proveedor.
        PaymentChargebackService.RefreshTarget initial=chargebacks.refreshTarget(id);
        PaymentGateway gateway=gateways.requireImplemented(initial.provider());
        if (!gateway.supportsDisputeEvidence()) throw new ResponseStatusException(HttpStatus.CONFLICT,"El proveedor no admite carga de evidencia desde Pensiones");
        PaymentGateway.PaymentDisputeResult fresh=gateway.getDispute(new PaymentGateway.PaymentDisputeLookupRequest(initial.providerChargebackId(),initial.providerPaymentId()));
        var synced=chargebacks.sync(initial.provider(),fresh);
        evidenceState.resolveAfterProviderRefresh(synced.getId());

        String manifestJson=manifestJson(evidence.files());
        PaymentChargebackEvidenceStateService.UploadTarget target=evidenceState.begin(id,actor,reason,manifestJson,evidence.files().size(),evidence.totalBytes());
        List<PaymentGateway.PaymentDisputeEvidenceFile> providerFiles=evidence.files().stream()
                .map(f -> new PaymentGateway.PaymentDisputeEvidenceFile(f.filename(),f.contentType(),f.size(),f.sha256(),f.content()))
                .toList();

        try {
            gateway.submitDisputeEvidence(new PaymentGateway.PaymentDisputeEvidenceRequest(target.providerChargebackId(),providerFiles));
        } catch (RuntimeException uploadError) {
            // Si el POST tuvo un resultado incierto, bloqueamos el retry y consultamos el recurso.
            evidenceState.markUnknown(id);
            try {
                PaymentGateway.PaymentDisputeResult verify=gateway.getDispute(new PaymentGateway.PaymentDisputeLookupRequest(target.providerChargebackId(),target.providerPaymentId()));
                var verified=chargebacks.sync(target.provider(),verify);
                PaymentChargebackEvidenceStateService.Resolution resolution=evidenceState.resolveAfterProviderRefresh(verified.getId());
                if (resolution==PaymentChargebackEvidenceStateService.Resolution.CONFIRMED_SUBMITTED) return chargebacks.detail(verified.getId());
                if (resolution==PaymentChargebackEvidenceStateService.Resolution.CONFIRMED_NOT_SUBMITTED) throw uploadError;
            } catch (RuntimeException verificationError) {
                if (verificationError == uploadError) throw uploadError;
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "No se pudo confirmar si Mercado Pago recibió la evidencia. Sincronizá el contracargo antes de reintentar");
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Mercado Pago no devolvió un estado concluyente para la carga. Sincronizá antes de reintentar");
        }

        // HTTP 2xx en el endpoint de documentación implica recepción; Mercado Pago pasa a review_pending.
        evidenceState.complete(id);
        try {
            PaymentGateway.PaymentDisputeResult after=gateway.getDispute(new PaymentGateway.PaymentDisputeLookupRequest(target.providerChargebackId(),target.providerPaymentId()));
            chargebacks.sync(target.provider(),after);
        } catch (RuntimeException ignored) {
            // La evidencia ya fue aceptada por el POST; una falla de refresco posterior no revierte la operación.
        }
        return chargebacks.detail(id);
    }

    private EvidenceBundle readEvidence(List<MultipartFile> files) {
        if (files==null || files.isEmpty()) throw bad("Debes adjuntar al menos un archivo");
        if (files.size()>MAX_FILES) throw bad("Puedes adjuntar como máximo "+MAX_FILES+" archivos por envío");
        List<EvidenceFile> out=new ArrayList<>();
        long total=0;
        for (MultipartFile file:files) {
            if (file==null || file.isEmpty()) throw bad("Los archivos de evidencia no pueden estar vacíos");
            long declared=file.getSize();
            if (declared<=0 || declared>MAX_TOTAL_BYTES || total+declared>MAX_TOTAL_BYTES) throw bad("La evidencia no puede superar 10 MB en total");
            String filename=safeFilename(file.getOriginalFilename());
            byte[] content;
            try { content=file.getBytes(); } catch (IOException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"No se pudo leer "+filename); }
            if (content.length==0 || total+content.length>MAX_TOTAL_BYTES) throw bad("La evidencia no puede superar 10 MB en total");
            String contentType=detectType(filename,content);
            String sha256=sha256(content);
            out.add(new EvidenceFile(filename,contentType,content.length,sha256,content));
            total+=content.length;
        }
        return new EvidenceBundle(List.copyOf(out),total);
    }

    private String safeFilename(String original) {
        String value=original==null?"":original.replace('\\','/');
        int slash=value.lastIndexOf('/');
        if (slash>=0) value=value.substring(slash+1);
        value=value.trim();
        if (value.isBlank() || value.length()>180) throw bad("Nombre de archivo inválido");
        for (int i=0;i<value.length();i++) if (Character.isISOControl(value.charAt(i)) || value.charAt(i)=='\"') throw bad("Nombre de archivo inválido");
        return value;
    }

    private String detectType(String filename, byte[] content) {
        String lower=filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pdf") && starts(content,new int[]{0x25,0x50,0x44,0x46,0x2d})) return "application/pdf";
        if (lower.endsWith(".jpg") && starts(content,new int[]{0xff,0xd8,0xff})) return "image/jpeg";
        if (lower.endsWith(".png") && starts(content,new int[]{0x89,0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a})) return "image/png";
        throw bad("Solo se admiten PDF, JPG y PNG válidos");
    }

    private boolean starts(byte[] content,int[] signature) {
        if (content.length<signature.length) return false;
        for (int i=0;i<signature.length;i++) if ((content[i]&0xff)!=signature[i]) return false;
        return true;
    }

    private String sha256(byte[] content) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)); }
        catch (Exception e) { throw new IllegalStateException("No se pudo calcular la huella de la evidencia",e); }
    }

    private String manifestJson(List<EvidenceFile> files) {
        List<Map<String,Object>> manifest=new ArrayList<>();
        for (EvidenceFile file:files) {
            Map<String,Object> item=new LinkedHashMap<>();
            item.put("filename",file.filename()); item.put("contentType",file.contentType()); item.put("size",file.size()); item.put("sha256",file.sha256());
            manifest.add(item);
        }
        try { return objectMapper.writeValueAsString(manifest); }
        catch (Exception e) { throw new IllegalStateException("No se pudo serializar la metadata de evidencia",e); }
    }

    private ResponseStatusException bad(String message){return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}

    private record EvidenceFile(String filename,String contentType,long size,String sha256,byte[] content){}
    private record EvidenceBundle(List<EvidenceFile> files,long totalBytes){}
}
