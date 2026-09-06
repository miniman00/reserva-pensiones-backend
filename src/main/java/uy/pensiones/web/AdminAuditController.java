package uy.pensiones.web;

import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uy.pensiones.enums.AdminAuditAction;
import uy.pensiones.enums.AdminAuditEntityType;
import uy.pensiones.service.AdminAuditService;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/admin/audit")
@PreAuthorize("hasAuthority('BACKOFFICE_AUDIT_READ')")
public class AdminAuditController {

    private final AdminAuditService service;

    public AdminAuditController(AdminAuditService service) {
        this.service = service;
    }

    @GetMapping
    public Page<AdminAuditService.AdminAuditDTO> list(
            @RequestParam(required = false) Long actorId,
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) AdminAuditAction action,
            @RequestParam(required = false) AdminAuditEntityType entityType,
            @RequestParam(required = false) String entityId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createdFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createdTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return service.list(actorId, actor, action, entityType, entityId, createdFrom, createdTo, page, size);
    }
}
