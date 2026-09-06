package uy.pensiones.service;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import uy.pensiones.enums.AdminAuditAction;
import uy.pensiones.enums.AdminAuditEntityType;
import uy.pensiones.model.BackofficeUser;
import uy.pensiones.model.Pension;
import uy.pensiones.model.StudyCenterCatalog;
import uy.pensiones.repo.AdminStudyCenterQueryRepository;
import uy.pensiones.repo.StudyCenterCatalogRepository;
import uy.pensiones.web.dto.StudyCenterCatalogDTO;
import uy.pensiones.web.dto.StudyCenterCatalogUpdateRequest;

import java.text.Normalizer;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

@Service
public class StudyCenterCatalogService {

    private static final String ASSOCIATION_NORMALIZATION_SQL =
            "LOWER(TRANSLATE(TRIM(REGEXP_REPLACE(study_center, '[[:space:]]+', ' ', 'g')), "
                    + "'ÁÉÍÓÚÜÑáéíóúüñ', 'AEIOUUNaeiouun'))";

    private final StudyCenterCatalogRepository repository;
    private final AdminStudyCenterQueryRepository adminQueries;
    private final AdminAuditService audit;
    private final JdbcTemplate jdbc;

    public StudyCenterCatalogService(StudyCenterCatalogRepository repository,
                                     AdminStudyCenterQueryRepository adminQueries,
                                     AdminAuditService audit,
                                     JdbcTemplate jdbc) {
        this.repository = repository;
        this.adminQueries = adminQueries;
        this.audit = audit;
        this.jdbc = jdbc;
    }

    public static String normalizeDisplayName(String raw) {
        if (raw == null) return null;
        String value = raw.replaceAll("\\s+", " ").trim();
        return value.isEmpty() ? null : value;
    }

    public static String normalizeKey(String raw) {
        String display = normalizeDisplayName(raw);
        if (display == null) return null;
        String withoutMarks = Normalizer.normalize(display, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return withoutMarks.toLowerCase(Locale.ROOT);
    }

    @Transactional
    public void ensureCatalogEntries(Pension pension, Collection<String> names) {
        if (names == null || names.isEmpty()) return;

        for (String raw : names) {
            String name = normalizeDisplayName(raw);
            String key = normalizeKey(raw);
            if (name == null || key == null) continue;

            repository.findByNormalizedName(key).orElseGet(() -> repository.save(
                    StudyCenterCatalog.builder()
                            .name(name)
                            .normalizedName(key)
                            .countryCode(cleanCountry(pension == null ? null : pension.getCountryCode()))
                            .city(clean(pension == null ? null : pension.getCity()))
                            .verified(false)
                            .active(true)
                            .build()
            ));
        }
    }

    /** Solo centros activos pueden resolver búsquedas públicas por distancia. */
    public Optional<StudyCenterCatalog> findByName(String name) {
        String key = normalizeKey(name);
        return key == null ? Optional.empty() : repository.findByNormalizedName(key)
                .filter(item -> Boolean.TRUE.equals(item.getActive()));
    }

    public List<StudyCenterCatalogDTO> resolvePublicCatalog(Collection<String> publicNames) {
        return resolvePublicCatalog(publicNames, "", 100);
    }

    public List<StudyCenterCatalogDTO> resolvePublicCatalog(Collection<String> publicNames, String q, int limit) {
        int safeLimit = Math.min(200, Math.max(1, limit));
        String needle = normalizeDisplayName(q);
        if (needle == null) needle = "";

        LinkedHashMap<String, String> displayByKey = new LinkedHashMap<>();
        for (String raw : publicNames == null ? List.<String>of() : publicNames) {
            if (displayByKey.size() >= safeLimit) break;
            String display = normalizeDisplayName(raw);
            String key = normalizeKey(raw);
            if (display != null && key != null) displayByKey.putIfAbsent(key, display);
        }

        Map<String, StudyCenterCatalog> catalogByKey = new HashMap<>();
        Set<String> inactiveKeys = new HashSet<>();
        if (!displayByKey.isEmpty()) {
            repository.findByNormalizedNameIn(displayByKey.keySet()).forEach(item -> {
                if (Boolean.TRUE.equals(item.getActive())) {
                    catalogByKey.put(item.getNormalizedName(), item);
                } else {
                    inactiveKeys.add(item.getNormalizedName());
                }
            });
        }
        inactiveKeys.forEach(displayByKey::remove);

        var verifiedPage = PageRequest.of(0, safeLimit, Sort.by(Sort.Direction.ASC, "name"));
        repository.findByVerifiedTrueAndActiveTrueAndNameContainingIgnoreCase(needle, verifiedPage)
                .forEach(item -> {
                    catalogByKey.putIfAbsent(item.getNormalizedName(), item);
                    displayByKey.putIfAbsent(item.getNormalizedName(), item.getName());
                });

        return displayByKey.entrySet().stream()
                .map(entry -> {
                    StudyCenterCatalog item = catalogByKey.get(entry.getKey());
                    if (item == null) {
                        return new StudyCenterCatalogDTO(
                                null, entry.getValue(), null, null, null, null, false, false
                        );
                    }
                    return StudyCenterCatalogDTO.of(item);
                })
                .sorted(Comparator.comparing(StudyCenterCatalogDTO::name, String.CASE_INSENSITIVE_ORDER))
                .limit(safeLimit)
                .toList();
    }

    @Transactional(readOnly = true)
    public Page<AdminStudyCenterDTO> adminList(String q,
                                               Boolean active,
                                               Boolean verified,
                                               Boolean geolocated,
                                               int page,
                                               int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(100, Math.max(1, size));
        String cleanQ = clean(q);
        var pageable = PageRequest.of(safePage, safeSize);
        return adminQueries.search(cleanQ == null ? "" : cleanQ, active, verified, geolocated, pageable).map(this::adminDto);
    }

    @Transactional(readOnly = true)
    public AdminStudyCenterSummaryDTO adminSummary() {
        var row = adminQueries.summary();
        return new AdminStudyCenterSummaryDTO(
                safe(row == null ? null : row.getTotal()),
                safe(row == null ? null : row.getActive()),
                safe(row == null ? null : row.getInactive()),
                safe(row == null ? null : row.getVerified()),
                safe(row == null ? null : row.getPending()),
                safe(row == null ? null : row.getMissingCoordinates()),
                safe(row == null ? null : row.getInUse())
        );
    }

    @Transactional(readOnly = true)
    public AdminStudyCenterDetailDTO adminDetail(Long id) {
        StudyCenterCatalog center = findCenter(id);
        var linked = adminQueries.recentLinkedPensions(center.getNormalizedName()).stream()
                .map(this::pensionDto)
                .toList();
        AdminStudyCenterDistanceDTO distances = distanceDto(center);
        AdminStudyCenterDTO base = adminDto(center);
        return new AdminStudyCenterDetailDTO(base, distances, linked);
    }

    @Transactional
    public AdminStudyCenterDTO create(StudyCenterCatalogUpdateRequest request, BackofficeUser actor) {
        String name = normalizeDisplayName(request.name());
        String key = normalizeKey(name);
        if (key == null) throw badRequest("Debes indicar el nombre del centro de estudio.");
        if (repository.findByNormalizedName(key).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ya existe un centro de estudio con ese nombre.");
        }

        validateCoordinates(request.lat(), request.lng(), Boolean.TRUE.equals(request.verified()));
        StudyCenterCatalog saved = repository.save(StudyCenterCatalog.builder()
                .name(name)
                .normalizedName(key)
                .city(clean(request.city()))
                .countryCode(cleanCountry(request.countryCode()))
                .lat(request.lat())
                .lng(request.lng())
                .verified(Boolean.TRUE.equals(request.verified()))
                .active(true)
                .build());

        audit.record(actor, AdminAuditAction.ADMIN_CREATE_STUDY_CENTER, AdminAuditEntityType.STUDY_CENTER,
                saved.getId(), null, snapshot(saved), clean(request.reason()));
        return adminDto(saved);
    }

    @Transactional
    public AdminStudyCenterDTO update(Long id, StudyCenterCatalogUpdateRequest request, BackofficeUser actor) {
        StudyCenterCatalog target = findCenter(id);
        String reason = audit.requireReason(request.reason());
        String name = normalizeDisplayName(request.name());
        String key = normalizeKey(name);
        if (key == null) throw badRequest("Debes indicar el nombre del centro de estudio.");

        repository.findByNormalizedName(key)
                .filter(other -> !Objects.equals(other.getId(), id))
                .ifPresent(other -> { throw new ResponseStatusException(HttpStatus.CONFLICT, "Ya existe un centro de estudio con ese nombre."); });

        validateCoordinates(request.lat(), request.lng(), Boolean.TRUE.equals(request.verified()));
        Map<String, Object> before = snapshot(target);
        String previousKey = target.getNormalizedName();
        boolean renamed = !Objects.equals(previousKey, key) || !Objects.equals(target.getName(), name);

        target.setName(name);
        target.setNormalizedName(key);
        target.setCity(clean(request.city()));
        target.setCountryCode(cleanCountry(request.countryCode()));
        target.setLat(request.lat());
        target.setLng(request.lng());
        target.setVerified(Boolean.TRUE.equals(request.verified()));
        StudyCenterCatalog saved = repository.save(target);

        if (renamed) propagateAssociationRename(previousKey, name);

        audit.record(actor, AdminAuditAction.ADMIN_UPDATE_STUDY_CENTER, AdminAuditEntityType.STUDY_CENTER,
                saved.getId(), before, snapshot(saved), reason);
        return adminDto(saved);
    }

    @Transactional
    public AdminStudyCenterDTO activate(Long id, String rawReason, BackofficeUser actor) {
        return changeActive(id, true, rawReason, actor);
    }

    @Transactional
    public AdminStudyCenterDTO deactivate(Long id, String rawReason, BackofficeUser actor) {
        return changeActive(id, false, rawReason, actor);
    }

    private AdminStudyCenterDTO changeActive(Long id, boolean active, String rawReason, BackofficeUser actor) {
        String reason = audit.requireReason(rawReason);
        StudyCenterCatalog center = findCenter(id);
        if (Boolean.TRUE.equals(center.getActive()) == active) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    active ? "El centro ya está activo." : "El centro ya está inactivo.");
        }

        Map<String, Object> before = snapshot(center);
        center.setActive(active);
        StudyCenterCatalog saved = repository.save(center);
        audit.record(actor,
                active ? AdminAuditAction.ADMIN_ACTIVATE_STUDY_CENTER : AdminAuditAction.ADMIN_DEACTIVATE_STUDY_CENTER,
                AdminAuditEntityType.STUDY_CENTER, saved.getId(), before, snapshot(saved), reason);
        return adminDto(saved);
    }

    private void propagateAssociationRename(String oldKey, String newName) {
        if (oldKey == null || newName == null) return;

        // Bases heredadas pueden contener variantes de mayúsculas/acentos del mismo centro para una pensión.
        // Conservamos una sola fila antes de renombrar para no violar el índice único (pension_id, study_center).
        String deduplicateSql = """
                DELETE FROM pension_study_centers
                WHERE ctid IN (
                    SELECT ctid FROM (
                        SELECT ctid,
                               ROW_NUMBER() OVER (
                                   PARTITION BY pension_id
                                   ORDER BY CASE WHEN study_center = ? THEN 0 ELSE 1 END, study_center
                               ) AS rn
                        FROM pension_study_centers
                        WHERE %s = ?
                    ) variants
                    WHERE variants.rn > 1
                )
                """.formatted(ASSOCIATION_NORMALIZATION_SQL);
        jdbc.update(deduplicateSql, newName, oldKey);

        jdbc.update("UPDATE pension_study_centers SET study_center = ? WHERE "
                + ASSOCIATION_NORMALIZATION_SQL + " = ? AND study_center <> ?", newName, oldKey, newName);
    }

    private AdminStudyCenterDTO adminDto(AdminStudyCenterQueryRepository.AdminStudyCenterRow row) {
        return new AdminStudyCenterDTO(
                row.getId(), row.getName(), row.getCity(), row.getCountryCode(), row.getLat(), row.getLng(),
                Boolean.TRUE.equals(row.getVerified()), Boolean.TRUE.equals(row.getActive()),
                row.getLat() != null && row.getLng() != null,
                safe(row.getUsageCount()), safe(row.getPublishedPensionCount()), safe(row.getVisiblePensionCount()),
                toOffsetDateTime(row.getCreatedAt()), toOffsetDateTime(row.getUpdatedAt())
        );
    }

    private AdminStudyCenterDTO adminDto(StudyCenterCatalog center) {
        long usage = linkedCount(center.getNormalizedName());
        long published = publishedLinkedCount(center.getNormalizedName(), false);
        long visible = publishedLinkedCount(center.getNormalizedName(), true);
        return new AdminStudyCenterDTO(
                center.getId(), center.getName(), center.getCity(), center.getCountryCode(), center.getLat(), center.getLng(),
                Boolean.TRUE.equals(center.getVerified()), Boolean.TRUE.equals(center.getActive()),
                center.getLat() != null && center.getLng() != null,
                usage, published, visible, center.getCreatedAt(), center.getUpdatedAt()
        );
    }

    private long linkedCount(String key) {
        Long value = jdbc.queryForObject("SELECT COUNT(DISTINCT pension_id) FROM pension_study_centers WHERE "
                + ASSOCIATION_NORMALIZATION_SQL + " = ?", Long.class, key);
        return safe(value);
    }

    private long publishedLinkedCount(String key, boolean onlyVisible) {
        String visibility = onlyVisible
                ? " AND COALESCE(p.moderation_blocked, false) = false AND COALESCE(u.suspended, false) = false"
                : "";
        Long value = jdbc.queryForObject("""
                SELECT COUNT(DISTINCT p.id)
                FROM pension_study_centers psc
                JOIN pensions p ON p.id = psc.pension_id
                LEFT JOIN users u ON u.id = COALESCE(p.owner_id, p.created_by_id)
                WHERE """ + ASSOCIATION_NORMALIZATION_SQL.replace("study_center", "psc.study_center")
                + " = ? AND p.status = 'PUBLISHED'" + visibility, Long.class, key);
        return safe(value);
    }

    private AdminStudyCenterDistanceDTO distanceDto(StudyCenterCatalog center) {
        if (center.getLat() == null || center.getLng() == null) return new AdminStudyCenterDistanceDTO(0, 0, 0);
        var row = adminQueries.distanceMetrics(center.getLat(), center.getLng());
        return new AdminStudyCenterDistanceDTO(
                safe(row == null ? null : row.getWithin1Km()),
                safe(row == null ? null : row.getWithin3Km()),
                safe(row == null ? null : row.getWithin5Km())
        );
    }

    private AdminStudyCenterPensionDTO pensionDto(AdminStudyCenterQueryRepository.AdminStudyCenterPensionRow row) {
        return new AdminStudyCenterPensionDTO(
                row.getId(), row.getName(), row.getCity(), row.getState(), row.getStatus(),
                Boolean.TRUE.equals(row.getModerationBlocked()), Boolean.TRUE.equals(row.getFeatured()),
                row.getOwnerId(), row.getOwnerName(), row.getOwnerEmail(), Boolean.TRUE.equals(row.getOwnerSuspended()),
                toOffsetDateTime(row.getUpdatedAt())
        );
    }

    private OffsetDateTime toOffsetDateTime(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    private StudyCenterCatalog findCenter(Long id) {
        if (id == null || id <= 0) throw badRequest("Identificador de centro inválido.");
        return repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Centro de estudio no encontrado."));
    }

    private Map<String, Object> snapshot(StudyCenterCatalog center) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", center.getId());
        out.put("name", center.getName());
        out.put("normalizedName", center.getNormalizedName());
        out.put("city", center.getCity());
        out.put("countryCode", center.getCountryCode());
        out.put("lat", center.getLat());
        out.put("lng", center.getLng());
        out.put("verified", Boolean.TRUE.equals(center.getVerified()));
        out.put("active", Boolean.TRUE.equals(center.getActive()));
        return out;
    }

    private void validateCoordinates(Double lat, Double lng, boolean verified) {
        if ((lat == null) != (lng == null)) {
            throw badRequest("Debes indicar latitud y longitud juntas.");
        }
        if (lat != null && (lat < -90 || lat > 90 || lng < -180 || lng > 180)) {
            throw badRequest("Las coordenadas del centro de estudio no son válidas.");
        }
        if (verified && lat == null) {
            throw badRequest("Un centro verificado debe tener coordenadas.");
        }
    }

    private String clean(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String cleanCountry(String value) {
        String cleaned = clean(value);
        return cleaned == null ? null : cleaned.toUpperCase(Locale.ROOT);
    }

    private long safe(Long value) { return value == null ? 0L : value; }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    public record AdminStudyCenterDTO(
            Long id, String name, String city, String countryCode, Double lat, Double lng,
            boolean verified, boolean active, boolean geolocated,
            long usageCount, long publishedPensionCount, long visiblePensionCount,
            OffsetDateTime createdAt, OffsetDateTime updatedAt
    ) {}

    public record AdminStudyCenterSummaryDTO(
            long total, long active, long inactive, long verified, long pending,
            long missingCoordinates, long inUse
    ) {}

    public record AdminStudyCenterDistanceDTO(long within1Km, long within3Km, long within5Km) {}

    public record AdminStudyCenterPensionDTO(
            Long id, String name, String city, String state, String status,
            boolean moderationBlocked, boolean featured,
            Long ownerId, String ownerName, String ownerEmail, boolean ownerSuspended,
            OffsetDateTime updatedAt
    ) {}

    public record AdminStudyCenterDetailDTO(
            AdminStudyCenterDTO center,
            AdminStudyCenterDistanceDTO nearbyVisiblePensions,
            List<AdminStudyCenterPensionDTO> recentLinkedPensions
    ) {}
}
