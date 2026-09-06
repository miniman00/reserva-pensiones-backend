package uy.pensiones.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import uy.pensiones.enums.AdminAuditAction;
import uy.pensiones.enums.AdminAuditEntityType;
import uy.pensiones.enums.NotificationType;
import uy.pensiones.enums.PensionReportReason;
import uy.pensiones.enums.PensionReportResolution;
import uy.pensiones.enums.PensionReportStatus;
import uy.pensiones.enums.PensionStatus;
import uy.pensiones.enums.UserRole;
import uy.pensiones.model.BackofficeUser;
import uy.pensiones.model.Pension;
import uy.pensiones.model.PensionReport;
import uy.pensiones.model.User;
import uy.pensiones.repo.PensionReportRepository;
import uy.pensiones.repo.PensionRepository;
import uy.pensiones.repo.UserRepository;
import uy.pensiones.web.dto.PensionReportCreateRequest;
import uy.pensiones.web.dto.PensionReportDTO;
import uy.pensiones.web.dto.PensionReportReviewRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class PensionReportService {

    private static final EnumSet<PensionReportStatus> OPEN_STATUSES =
            EnumSet.of(PensionReportStatus.NEW, PensionReportStatus.UNDER_REVIEW);

    private final PensionReportRepository reports;
    private final PensionRepository pensions;
    private final UserRepository users;
    private final PensionReportProtectionService protection;
    private final NotificationService notifications;
    private final AdminAuditService audit;
    private final AdminPensionService adminPensions;
    private final AdminUserService adminUsers;

    public PensionReportService(PensionReportRepository reports,
                                PensionRepository pensions,
                                UserRepository users,
                                PensionReportProtectionService protection,
                                NotificationService notifications,
                                AdminAuditService audit,
                                AdminPensionService adminPensions,
                                AdminUserService adminUsers) {
        this.reports = reports;
        this.pensions = pensions;
        this.users = users;
        this.protection = protection;
        this.notifications = notifications;
        this.audit = audit;
        this.adminPensions = adminPensions;
        this.adminUsers = adminUsers;
    }

    @Transactional
    public Long create(Long pensionId, PensionReportCreateRequest request, OAuth2User principal) {
        Pension pension = pensions.findWithOwnerById(pensionId)
                .filter(p -> p.getStatus() == PensionStatus.PUBLISHED
                        && !Boolean.TRUE.equals(p.getModerationBlocked())
                        && !ownerSuspended(p))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pensión no encontrada"));

        // Honeypot: respuesta exitosa simulada, sin persistencia.
        if (hasText(request.website())) return null;

        User reporter = currentOrNull(principal);
        String visitorHash = reporter == null && hasText(request.visitorKey())
                ? sha256(request.visitorKey().trim())
                : null;
        String clientKey = reporter != null
                ? "user:" + reporter.getId()
                : visitorHash != null ? "visitor:" + visitorHash : "anonymous";

        protection.check(clientKey);

        OffsetDateTime since = OffsetDateTime.now(ZoneOffset.UTC).minusDays(7);
        boolean duplicate = reporter != null
                ? reports.existsByPension_IdAndReporter_IdAndReasonAndStatusInAndCreatedAtAfter(
                        pensionId, reporter.getId(), request.reason(), OPEN_STATUSES, since)
                : visitorHash != null && reports.existsByPension_IdAndReporterKeyHashAndReasonAndStatusInAndCreatedAtAfter(
                        pensionId, visitorHash, request.reason(), OPEN_STATUSES, since);

        if (duplicate) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Ya recibimos un reporte similar de este anuncio y todavía está en revisión."
            );
        }

        PensionReport report = PensionReport.builder()
                .pension(pension)
                .reporter(reporter)
                .reporterKeyHash(visitorHash)
                .reporterEmail(normalizeEmail(request.reporterEmail()))
                .reason(request.reason())
                .details(trimToNull(request.details()))
                .status(PensionReportStatus.NEW)
                .termsAcceptedVersion(request.termsVersion())
                .privacyAcceptedVersion(request.privacyVersion())
                .consentAcceptedAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build();

        return reports.save(report).getId();
    }

    @Transactional(readOnly = true)
    public Page<PensionReportDTO> list(PensionReportStatus status,
                                       Long pensionId,
                                       PensionReportReason reason,
                                       PensionReportResolution resolution,
                                       int page,
                                       int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(100, Math.max(1, size));
        if (pensionId != null && pensionId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Identificador de pensión inválido");
        }
        PageRequest pageable = PageRequest.of(
                safePage,
                safeSize,
                Sort.by(Sort.Direction.DESC, "createdAt")
        );
        return reports.searchAdmin(status, pensionId, reason, resolution, pageable).map(this::toDto);
    }

    @Transactional(readOnly = true)
    public AdminReportDetailDTO detail(Long reportId) {
        PensionReport report = findReport(reportId);
        Pension pension = report.getPension();
        User owner = responsibleOwner(pension);
        List<RelatedReportDTO> related = reports.findTop10ByPension_IdOrderByCreatedAtDesc(pension.getId()).stream()
                .filter(item -> !item.getId().equals(report.getId()))
                .map(this::relatedDto)
                .toList();
        long reportCount = reports.countByPension_Id(pension.getId());
        long openReportCount = reports.countByPension_IdAndStatusIn(pension.getId(), OPEN_STATUSES);

        return new AdminReportDetailDTO(
                toDto(report),
                new ReportPensionContextDTO(
                        pension.getId(), pension.getName(), pension.getStatus(),
                        Boolean.TRUE.equals(pension.getModerationBlocked()), Boolean.TRUE.equals(pension.getFeatured()),
                        pension.getCity(), pension.getState(), pension.getCreatedAt(), pension.getUpdatedAt(),
                        reportCount, openReportCount
                ),
                new ReportOwnerContextDTO(
                        owner.getId(), owner.getName(), owner.getEmail(), owner.getRole(), owner.isSuspended(),
                        owner.getSuspendedAt(), owner.getSuspensionReason(), owner.isEmailVerified(), owner.getCreatedAt()
                ),
                related
        );
    }

    @Transactional
    public PensionReportDTO review(Long reportId, PensionReportReviewRequest request, BackofficeUser admin) {
        PensionReport report = findReport(reportId);
        PensionReportStatus nextStatus = request.status();
        String notes = trimToNull(request.adminNotes());
        AdminAuditAction action;
        PensionReportResolution nextResolution;

        if (nextStatus == PensionReportStatus.NEW) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No se puede devolver una denuncia al estado NEW");
        }

        if (nextStatus == PensionReportStatus.UNDER_REVIEW) {
            requireStatus(report, PensionReportStatus.NEW,
                    "Solo una denuncia nueva puede tomarse para revisión");
            nextResolution = null;
            action = AdminAuditAction.ADMIN_START_REPORT_REVIEW;
        } else if (nextStatus == PensionReportStatus.RESOLVED) {
            requireUnderReview(report);
            notes = audit.requireReason(notes);
            nextResolution = PensionReportResolution.NO_ACTION;
            action = AdminAuditAction.ADMIN_RESOLVE_REPORT;
        } else if (nextStatus == PensionReportStatus.DISMISSED) {
            requireUnderReview(report);
            notes = audit.requireReason(notes);
            nextResolution = null;
            action = AdminAuditAction.ADMIN_DISMISS_REPORT;
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Estado de denuncia inválido");
        }

        Map<String, Object> before = reportSnapshot(report);
        report.setStatus(nextStatus);
        report.setResolution(nextResolution);
        report.setAdminNotes(notes);
        markReviewed(report, admin);
        PensionReport saved = reports.save(report);
        audit.record(admin, action, AdminAuditEntityType.REPORT, saved.getId(),
                before, reportSnapshot(saved), notes);
        return toDto(saved);
    }

    @Transactional
    public PensionReportDTO warnOwner(Long reportId, String adminNotes, BackofficeUser admin) {
        PensionReport report = findReport(reportId);
        requireUnderReview(report);
        User owner = responsibleOwner(report.getPension());
        if (owner.isSuspended()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "La cuenta responsable está suspendida; selecciona una resolución acorde a su estado actual");
        }
        String reason = audit.requireReason(adminNotes);
        Map<String, Object> before = reportSnapshot(report);

        report.setStatus(PensionReportStatus.RESOLVED);
        report.setResolution(PensionReportResolution.WARNING);
        report.setAdminNotes(reason);
        markReviewed(report, admin);
        reports.save(report);

        audit.record(admin, AdminAuditAction.ADMIN_WARN_OWNER, AdminAuditEntityType.REPORT, report.getId(),
                before, reportSnapshot(report), reason);

        try {
            notifications.create(
                    owner,
                    NotificationType.MODERATION_WARNING,
                    "Advertencia de moderación",
                    "Recibiste una advertencia relacionada con la publicación \"" + report.getPension().getName()
                            + "\". Revisa que la información publicada sea correcta y cumpla las reglas de la plataforma.",
                    "/profile/pensions/" + report.getPension().getId() + "/edit"
            );
        } catch (RuntimeException ignored) {
            // La resolución y su auditoría no dependen de una notificación secundaria.
        }

        return toDto(report);
    }

    @Transactional
    public PensionReportDTO pausePension(Long reportId, String adminNotes, BackofficeUser admin) {
        PensionReport report = findReport(reportId);
        requireUnderReview(report);
        Pension pension = report.getPension();
        String reason = audit.requireReason(adminNotes);
        Map<String, Object> reportBefore = reportSnapshot(report);

        adminPensions.block(pension.getId(), reason, admin);

        report.setStatus(PensionReportStatus.RESOLVED);
        report.setResolution(PensionReportResolution.PENSION_BLOCKED);
        report.setAdminNotes(reason);
        markReviewed(report, admin);
        reports.save(report);
        audit.record(admin, AdminAuditAction.ADMIN_RESOLVE_REPORT, AdminAuditEntityType.REPORT, report.getId(),
                reportBefore, reportSnapshot(report), reason);

        return toDto(report);
    }

    @Transactional
    public PensionReportDTO confirmViolation(Long reportId, String adminNotes, BackofficeUser admin) {
        PensionReport report = findReport(reportId);
        requireUnderReview(report);
        Pension pension = report.getPension();
        User owner = responsibleOwner(pension);
        if (owner.isSuspended()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "La cuenta responsable ya está suspendida");
        }
        String notes = audit.requireReason(adminNotes);
        Map<String, Object> reportBefore = reportSnapshot(report);

        if (!Boolean.TRUE.equals(pension.getModerationBlocked())) {
            adminPensions.block(pension.getId(), notes, admin, false);
        }
        adminUsers.suspend(owner.getId(), notes, admin);

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        report.setStatus(PensionReportStatus.RESOLVED);
        report.setResolution(PensionReportResolution.OWNER_SUSPENDED);
        report.setAdminNotes(notes);
        markReviewed(report, admin, now);
        reports.save(report);
        audit.record(admin, AdminAuditAction.ADMIN_CONFIRM_VIOLATION, AdminAuditEntityType.REPORT, report.getId(),
                reportBefore, reportSnapshot(report), notes);

        try {
            notifications.create(
                    owner,
                    NotificationType.MODERATION_PENSION_PAUSED,
                    "Cuenta suspendida por moderación",
                    "Se confirmó una infracción relacionada con la publicación \"" + pension.getName()
                            + "\". Tu cuenta quedó suspendida y tus anuncios dejaron de estar disponibles públicamente. Contacta a soporte si necesitas solicitar una revisión.",
                    "/"
            );
        } catch (RuntimeException ignored) {
            // La decisión de moderación no debe revertirse por una falla de notificación.
        }

        return toDto(report);
    }

    @Transactional
    public PensionReportDTO reactivateOwner(Long reportId, String adminNotes, BackofficeUser admin) {
        PensionReport report = findReport(reportId);
        User owner = responsibleOwner(report.getPension());
        adminUsers.reactivate(owner.getId(), adminNotes, admin);
        return toDto(report);
    }

    @Transactional
    public PensionReportDTO releasePension(Long reportId, String adminNotes, BackofficeUser admin) {
        PensionReport report = findReport(reportId);
        adminPensions.unblock(report.getPension().getId(), adminNotes, admin);
        return toDto(report);
    }

    private PensionReport findReport(Long reportId) {
        if (reportId == null || reportId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Identificador de denuncia inválido");
        }
        return reports.findById(reportId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Reporte no encontrado"));
    }

    private void requireUnderReview(PensionReport report) {
        requireStatus(report, PensionReportStatus.UNDER_REVIEW,
                "Primero debes tomar la denuncia y dejarla EN REVISIÓN antes de resolverla");
    }

    private void requireStatus(PensionReport report, PensionReportStatus expected, String message) {
        if (report.getStatus() != expected) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, message);
        }
    }

    private void markReviewed(PensionReport report, BackofficeUser admin) {
        markReviewed(report, admin, OffsetDateTime.now(ZoneOffset.UTC));
    }

    private void markReviewed(PensionReport report, BackofficeUser admin, OffsetDateTime reviewedAt) {
        report.setReviewedByBackoffice(admin);
        report.setReviewedBy(null);
        report.setReviewedAt(reviewedAt);
    }

    private Map<String, Object> reportSnapshot(PensionReport report) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", report.getId());
        value.put("pensionId", report.getPension() == null ? null : report.getPension().getId());
        value.put("status", report.getStatus());
        value.put("resolution", report.getResolution());
        value.put("adminNotes", report.getAdminNotes());
        value.put("reviewedByBackofficeUserId", report.getReviewedByBackoffice() == null ? null : report.getReviewedByBackoffice().getId());
        value.put("reviewedAt", report.getReviewedAt());
        return value;
    }

    private PensionReportDTO toDto(PensionReport report) {
        User reporter = report.getReporter();
        User legacyReviewer = report.getReviewedBy();
        BackofficeUser reviewer = report.getReviewedByBackoffice();
        return new PensionReportDTO(
                report.getId(),
                report.getPension().getId(),
                report.getPension().getName(),
                report.getPension().getStatus(),
                Boolean.TRUE.equals(report.getPension().getModerationBlocked()),
                ownerSuspended(report.getPension()),
                report.getReason(),
                report.getDetails(),
                report.getReporterEmail(),
                reporter == null ? null : reporter.getName(),
                report.getStatus(),
                report.getResolution(),
                report.getAdminNotes(),
                reviewer != null ? reviewer.getDisplayName() : (legacyReviewer == null ? null : legacyReviewer.getName()),
                report.getReviewedAt(),
                report.getCreatedAt(),
                report.getUpdatedAt()
        );
    }

    private RelatedReportDTO relatedDto(PensionReport report) {
        BackofficeUser reviewer = report.getReviewedByBackoffice();
        User legacyReviewer = report.getReviewedBy();
        return new RelatedReportDTO(
                report.getId(), report.getReason(), report.getStatus(), report.getResolution(), report.getAdminNotes(),
                reviewer != null ? reviewer.getDisplayName() : (legacyReviewer == null ? null : legacyReviewer.getName()),
                report.getReviewedAt(), report.getCreatedAt()
        );
    }

    private boolean ownerSuspended(Pension pension) {
        User owner = pension.getOwner() != null ? pension.getOwner() : pension.getCreatedBy();
        return owner != null && owner.isSuspended();
    }

    private User responsibleOwner(Pension pension) {
        User owner = pension.getOwner() != null ? pension.getOwner() : pension.getCreatedBy();
        if (owner == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "La pensión no tiene una cuenta responsable asociada.");
        }
        return owner;
    }

    private User currentOrNull(OAuth2User principal) {
        if (principal == null) return null;
        User appUser = principal.getAttribute("appUser");
        if (appUser != null) return users.findById(appUser.getId()).orElse(null);
        String email = principal.getAttribute("email");
        return email == null ? null : users.findByEmail(email).orElse(null);
    }

    private String normalizeEmail(String value) {
        String v = trimToNull(value);
        return v == null ? null : v.toLowerCase(Locale.ROOT);
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }

    public record ReportPensionContextDTO(
            Long id,
            String name,
            PensionStatus status,
            boolean moderationBlocked,
            boolean featured,
            String city,
            String state,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            long reportCount,
            long openReportCount
    ) {}

    public record ReportOwnerContextDTO(
            Long id,
            String name,
            String email,
            UserRole role,
            boolean suspended,
            OffsetDateTime suspendedAt,
            String suspensionReason,
            boolean emailVerified,
            OffsetDateTime createdAt
    ) {}

    public record RelatedReportDTO(
            Long id,
            PensionReportReason reason,
            PensionReportStatus status,
            PensionReportResolution resolution,
            String adminNotes,
            String reviewedBy,
            OffsetDateTime reviewedAt,
            OffsetDateTime createdAt
    ) {}

    public record AdminReportDetailDTO(
            PensionReportDTO report,
            ReportPensionContextDTO pension,
            ReportOwnerContextDTO owner,
            List<RelatedReportDTO> relatedReports
    ) {}
}
