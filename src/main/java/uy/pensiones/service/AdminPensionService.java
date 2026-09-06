package uy.pensiones.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import uy.pensiones.enums.AdminAuditAction;
import uy.pensiones.enums.AdminAuditEntityType;
import uy.pensiones.enums.NotificationType;
import uy.pensiones.enums.PensionReportReason;
import uy.pensiones.enums.PensionReportStatus;
import uy.pensiones.enums.PensionStatus;
import uy.pensiones.model.BackofficeUser;
import uy.pensiones.model.Pension;
import uy.pensiones.model.PensionReport;
import uy.pensiones.model.User;
import uy.pensiones.repo.AdminPensionQueryRepository;
import uy.pensiones.repo.PensionMediaRepository;
import uy.pensiones.repo.PensionMemberRepository;
import uy.pensiones.repo.PensionReportRepository;
import uy.pensiones.repo.PensionRepository;
import uy.pensiones.web.dto.MemberDTO;
import uy.pensiones.web.dto.PensionMediaDTO;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class AdminPensionService {

    private final AdminPensionQueryRepository pensionQueries;
    private final PensionRepository pensions;
    private final PensionMediaRepository media;
    private final PensionMemberRepository members;
    private final PensionReportRepository reports;
    private final AdminAuditService audit;
    private final NotificationService notifications;
    private final PensionMediaDtoMapper mediaMapper;

    public AdminPensionService(AdminPensionQueryRepository pensionQueries,
                               PensionRepository pensions,
                               PensionMediaRepository media,
                               PensionMemberRepository members,
                               PensionReportRepository reports,
                               AdminAuditService audit,
                               NotificationService notifications,
                               PensionMediaDtoMapper mediaMapper) {
        this.pensionQueries = pensionQueries;
        this.pensions = pensions;
        this.media = media;
        this.members = members;
        this.reports = reports;
        this.audit = audit;
        this.notifications = notifications;
        this.mediaMapper = mediaMapper;
    }

    @Transactional(readOnly = true)
    public Page<AdminPensionDTO> list(String q,
                                      String owner,
                                      String email,
                                      String city,
                                      String state,
                                      PensionStatus status,
                                      Boolean moderationBlocked,
                                      Boolean featured,
                                      Boolean available,
                                      LocalDate createdFrom,
                                      LocalDate createdTo,
                                      int page,
                                      int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.max(1, Math.min(size, 100));
        Pageable pageable = PageRequest.of(safePage, safeSize);
        if (createdFrom != null && createdTo != null && createdTo.isBefore(createdFrom)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La fecha final no puede ser anterior a la fecha inicial");
        }

        OffsetDateTime from = createdFrom == null ? null : createdFrom.atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime toExclusive = createdTo == null ? null : createdTo.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);

        return pensionQueries.search(
                clean(q, 140, "búsqueda"),
                clean(owner, 190, "propietario"),
                clean(email, 190, "email"),
                clean(city, 100, "ciudad"),
                clean(state, 100, "departamento"),
                status == null ? "" : status.name(), moderationBlocked, featured, available,
                from, toExclusive, pageable
        ).map(this::toDto);
    }

    @Transactional(readOnly = true)
    public AdminPensionDetailDTO detail(Long pensionId) {
        Pension pension = findPension(pensionId);
        User owner = responsibleOwner(pension);
        User createdBy = pension.getCreatedBy();
        AdminPensionQueryRepository.AdminPensionMetrics metrics = pensionQueries.findMetrics(pensionId);

        List<PensionMediaDTO> pensionMedia = media.findByPensionIdOrderBySortOrderAscIdAsc(pensionId)
                .stream().map(mediaMapper::toDto).toList();
        List<MemberDTO> pensionMembers = members.findByPensionId(pensionId)
                .stream().map(MemberDTO::of).toList();
        List<AdminPensionReportSummaryDTO> recentReports = reports.findTop10ByPension_IdOrderByCreatedAtDesc(pensionId)
                .stream().map(this::reportSummary).toList();

        return new AdminPensionDetailDTO(
                pension.getId(),
                pension.getName(),
                pension.getDescription(),
                pension.getOrg().getId(),
                pension.getOrg().getName(),
                pension.getCountryCode(),
                pension.getAddressLine1(),
                pension.getCity(),
                pension.getState(),
                pension.getPostalCode(),
                pension.getNeighborhood(),
                pension.getLat(),
                pension.getLng(),
                pension.getStatus(),
                Boolean.TRUE.equals(pension.getModerationBlocked()),
                Boolean.TRUE.equals(pension.getFeatured()),
                userSummary(owner),
                userSummary(createdBy),
                safe(pension.getCapacitySimple()),
                safe(pension.getCapacityMatrimonial()),
                safe(pension.getAvailableSimple()),
                safe(pension.getAvailableMatrimonial()),
                pension.getPriceSimple(),
                pension.getPriceMatrimonial(),
                pension.getAdmissionType() == null ? null : pension.getAdmissionType().name(),
                pension.getResidentProfile() == null ? null : pension.getResidentProfile().name(),
                safe(pension.getBathroomsCount()),
                pension.getBathroomType() == null ? null : pension.getBathroomType().name(),
                Boolean.TRUE.equals(pension.getHasParking()),
                pension.getContactName(),
                pension.getContactPhone(),
                pension.getContactWhatsapp(),
                Boolean.TRUE.equals(pension.getShowPhone()),
                Boolean.TRUE.equals(pension.getShowWhatsapp()),
                pension.getAmenities(),
                pension.getNearbyTags(),
                pension.getStudyCenters(),
                pension.getAvailabilityUpdatedAt(),
                pension.getCreatedAt(),
                pension.getUpdatedAt(),
                pensionMedia,
                pensionMembers,
                recentReports,
                metrics == null ? 0L : safe(metrics.getReportCount()),
                metrics == null ? 0L : safe(metrics.getOpenReportCount()),
                metrics == null ? 0L : safe(metrics.getViewCount()),
                metrics == null ? 0L : safe(metrics.getInquiryCount()),
                metrics == null ? 0L : safe(metrics.getFavoriteCount())
        );
    }

    @Transactional
    public AdminPensionActionDTO block(Long pensionId, String rawReason, BackofficeUser actor) {
        return block(pensionId, rawReason, actor, true);
    }

    /**
     * Variante interna reutilizable por flujos compuestos de moderación.
     * La auditoría siempre se registra; la notificación puede diferirse para evitar mensajes duplicados.
     */
    @Transactional
    public AdminPensionActionDTO block(Long pensionId, String rawReason, BackofficeUser actor, boolean notify) {
        String reason = audit.requireReason(rawReason);
        Pension pension = findPension(pensionId);
        if (Boolean.TRUE.equals(pension.getModerationBlocked())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "La pensión ya está bloqueada por moderación");
        }

        Map<String, Object> before = moderationSnapshot(pension);
        pension.setModerationBlocked(true);
        if (pension.getStatus() == PensionStatus.PUBLISHED) {
            pension.setStatus(PensionStatus.PAUSED);
        }
        pensions.save(pension);

        audit.record(actor, AdminAuditAction.ADMIN_BLOCK_PENSION, AdminAuditEntityType.PENSION,
                pension.getId(), before, moderationSnapshot(pension), reason);
        if (notify) {
            notifyOwner(pension, NotificationType.MODERATION_PENSION_PAUSED,
                    "Tu anuncio fue pausado por moderación",
                    "La publicación \"" + pension.getName()
                            + "\" fue bloqueada por moderación. Revisa la información antes de solicitar o realizar una nueva publicación.");
        }

        return actionDto(pension);
    }

    @Transactional
    public AdminPensionActionDTO unblock(Long pensionId, String rawReason, BackofficeUser actor) {
        String reason = audit.requireReason(rawReason);
        Pension pension = findPension(pensionId);
        if (!Boolean.TRUE.equals(pension.getModerationBlocked())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "La pensión no está bloqueada por moderación");
        }

        Map<String, Object> before = moderationSnapshot(pension);
        pension.setModerationBlocked(false);
        pensions.save(pension);

        audit.record(actor, AdminAuditAction.ADMIN_UNBLOCK_PENSION, AdminAuditEntityType.PENSION,
                pension.getId(), before, moderationSnapshot(pension), reason);
        notifyOwner(pension, NotificationType.MODERATION_HOLD_RELEASED,
                "Revisión de moderación finalizada",
                "La revisión de \"" + pension.getName()
                        + "\" finalizó. Puedes revisar el anuncio y volver a publicarlo cuando esté listo.");

        return actionDto(pension);
    }

    private Pension findPension(Long pensionId) {
        if (pensionId == null || pensionId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Identificador de pensión inválido");
        }
        return pensions.findWithOwnerById(pensionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pensión no encontrada"));
    }

    private Map<String, Object> moderationSnapshot(Pension pension) {
        User owner = responsibleOwner(pension);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("id", pension.getId());
        snapshot.put("name", pension.getName());
        snapshot.put("status", pension.getStatus());
        snapshot.put("moderationBlocked", Boolean.TRUE.equals(pension.getModerationBlocked()));
        snapshot.put("featured", Boolean.TRUE.equals(pension.getFeatured()));
        snapshot.put("ownerId", owner == null ? null : owner.getId());
        snapshot.put("ownerSuspended", owner != null && owner.isSuspended());
        return snapshot;
    }

    private User responsibleOwner(Pension pension) {
        return pension.getOwner() != null ? pension.getOwner() : pension.getCreatedBy();
    }

    private AdminPensionUserDTO userSummary(User user) {
        if (user == null) return null;
        return new AdminPensionUserDTO(
                user.getId(), user.getName(), user.getEmail(), user.getRole(), user.isSuspended(),
                user.getSuspensionReason(), user.isEmailVerified()
        );
    }

    private AdminPensionReportSummaryDTO reportSummary(PensionReport report) {
        String reviewedBy = report.getReviewedByBackoffice() != null
                ? report.getReviewedByBackoffice().getDisplayName()
                : report.getReviewedBy() != null ? report.getReviewedBy().getName() : null;
        return new AdminPensionReportSummaryDTO(
                report.getId(), report.getReason(), report.getStatus(), report.getDetails(),
                report.getReporterEmail(), reviewedBy, report.getReviewedAt(), report.getCreatedAt()
        );
    }

    private void notifyOwner(Pension pension, NotificationType type, String title, String message) {
        User owner = responsibleOwner(pension);
        if (owner == null) return;
        try {
            notifications.create(owner, type, title, message, "/profile/pensions/" + pension.getId() + "/edit");
        } catch (RuntimeException ignored) {
            // La acción administrativa y su auditoría no dependen de una notificación secundaria.
        }
    }

    private AdminPensionActionDTO actionDto(Pension pension) {
        return new AdminPensionActionDTO(
                pension.getId(), pension.getStatus(), Boolean.TRUE.equals(pension.getModerationBlocked())
        );
    }

    private AdminPensionDTO toDto(AdminPensionQueryRepository.AdminPensionRow row) {
        return new AdminPensionDTO(
                row.getId(), row.getName(), row.getOrganizationName(), row.getCity(), row.getState(),
                parseStatus(row.getStatus()), Boolean.TRUE.equals(row.getModerationBlocked()),
                Boolean.TRUE.equals(row.getFeatured()), row.getOwnerId(), row.getOwnerName(), row.getOwnerEmail(),
                Boolean.TRUE.equals(row.getOwnerSuspended()), safe(row.getCapacity()), safe(row.getAvailable()),
                toOffsetDateTime(row.getAvailabilityUpdatedAt()), toOffsetDateTime(row.getCreatedAt()),
                toOffsetDateTime(row.getUpdatedAt()), safe(row.getReportCount()),
                safe(row.getOpenReportCount()), safe(row.getViewCount()), safe(row.getInquiryCount())
        );
    }

    private OffsetDateTime toOffsetDateTime(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    private PensionStatus parseStatus(String value) {
        return value == null ? null : PensionStatus.valueOf(value);
    }

    private int safe(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }

    private long safe(Long value) {
        return value == null ? 0L : Math.max(0L, value);
    }

    private String clean(String value, int maxLength, String field) {
        if (value == null || value.isBlank()) return "";
        String cleaned = value.trim();
        if (cleaned.length() > maxLength) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El filtro de " + field + " supera el largo permitido");
        }
        return cleaned;
    }

    public record AdminPensionUserDTO(
            Long id,
            String name,
            String email,
            uy.pensiones.enums.UserRole role,
            boolean suspended,
            String suspensionReason,
            boolean emailVerified
    ) {}

    public record AdminPensionReportSummaryDTO(
            Long id,
            PensionReportReason reason,
            PensionReportStatus status,
            String details,
            String reporterEmail,
            String reviewedBy,
            OffsetDateTime reviewedAt,
            OffsetDateTime createdAt
    ) {}

    public record AdminPensionDetailDTO(
            Long id,
            String name,
            String description,
            Long organizationId,
            String organizationName,
            String countryCode,
            String addressLine1,
            String city,
            String state,
            String postalCode,
            String neighborhood,
            Double lat,
            Double lng,
            PensionStatus status,
            boolean moderationBlocked,
            boolean featured,
            AdminPensionUserDTO owner,
            AdminPensionUserDTO createdBy,
            int capacitySimple,
            int capacityMatrimonial,
            int availableSimple,
            int availableMatrimonial,
            BigDecimal priceSimple,
            BigDecimal priceMatrimonial,
            String admissionType,
            String residentProfile,
            int bathroomsCount,
            String bathroomType,
            boolean hasParking,
            String contactName,
            String contactPhone,
            String contactWhatsapp,
            boolean showPhone,
            boolean showWhatsapp,
            Set<uy.pensiones.enums.Amenity> amenities,
            Set<String> nearbyTags,
            Set<String> studyCenters,
            OffsetDateTime availabilityUpdatedAt,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            List<PensionMediaDTO> media,
            List<MemberDTO> members,
            List<AdminPensionReportSummaryDTO> recentReports,
            long reportCount,
            long openReportCount,
            long viewCount,
            long inquiryCount,
            long favoriteCount
    ) {}

    public record AdminPensionActionDTO(
            Long id,
            PensionStatus status,
            boolean moderationBlocked
    ) {}

    public record AdminPensionDTO(
            Long id,
            String name,
            String organizationName,
            String city,
            String state,
            PensionStatus status,
            boolean moderationBlocked,
            boolean featured,
            Long ownerId,
            String ownerName,
            String ownerEmail,
            boolean ownerSuspended,
            int capacity,
            int available,
            OffsetDateTime availabilityUpdatedAt,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            long reportCount,
            long openReportCount,
            long viewCount,
            long inquiryCount
    ) {}
}
