package uy.pensiones.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import uy.pensiones.service.MercadoPagoWebhookService;

@RestController
@RequestMapping("/api/public/payment-webhooks/mercado-pago")
public class MercadoPagoWebhookController {
    private final MercadoPagoWebhookService service;
    public MercadoPagoWebhookController(MercadoPagoWebhookService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<MercadoPagoWebhookService.WebhookResult> receive(
            @RequestParam(name = "data.id", required = false) String dataId,
            @RequestParam(name = "type", required = false) String type,
            @RequestHeader(name = "x-signature", required = false) String signature,
            @RequestHeader(name = "x-request-id", required = false) String requestId,
            @RequestBody(required = false) String body) {
        return ResponseEntity.ok(service.process(dataId, type, signature, requestId, body));
    }
}
