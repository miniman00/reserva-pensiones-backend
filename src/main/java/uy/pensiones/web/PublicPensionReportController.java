package uy.pensiones.web;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;
import uy.pensiones.service.PensionReportService;
import uy.pensiones.web.dto.PensionReportCreateRequest;

import java.util.Map;

@RestController
@RequestMapping("/api/public/pensions/{pensionId}/reports")
public class PublicPensionReportController {

    private final PensionReportService service;

    public PublicPensionReportController(PensionReportService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@PathVariable Long pensionId,
                                      @Valid @RequestBody PensionReportCreateRequest request,
                                      @AuthenticationPrincipal OAuth2User principal) {
        Long id = service.create(pensionId, request, principal);
        if (id == null) return Map.of("message", "Reporte recibido");
        return Map.of("id", id, "message", "Gracias. Revisaremos el anuncio reportado.");
    }
}
