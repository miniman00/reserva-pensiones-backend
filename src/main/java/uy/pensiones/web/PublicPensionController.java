package uy.pensiones.web;

import org.springframework.data.domain.*;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import uy.pensiones.config.AppProperties;
import uy.pensiones.service.PensionImageVariantService;
import uy.pensiones.web.dto.PensionCardDto;
import uy.pensiones.web.dto.PensionMapResultDto;
import uy.pensiones.web.dto.PublicPensionDetailDto;
import uy.pensiones.enums.AdmissionType;
import uy.pensiones.enums.Amenity;
import uy.pensiones.enums.BathroomType;
import uy.pensiones.enums.PensionStatus;
import uy.pensiones.enums.ResidentProfile;
import uy.pensiones.model.Pension;
import uy.pensiones.repo.PensionRepository;
import uy.pensiones.repo.PensionPromotionRepository;
import uy.pensiones.repo.PublicPensionDistanceRepository;
import uy.pensiones.pension.PensionSpecs;
import uy.pensiones.service.PensionDetailService;
import uy.pensiones.service.PensionCatalogQualityService;
import uy.pensiones.service.StudyCenterCatalogService;
import uy.pensiones.service.PublicTrafficProtectionService;
import uy.pensiones.service.PublicLocationPrivacy;
import uy.pensiones.web.dto.StudyCenterCatalogDTO;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/public/pensions")
public class PublicPensionController {

    private static final int MAP_LIMIT = 250;
    private static final int MAX_PUBLIC_PAGE = 100;
    private static final int MAX_PUBLIC_PAGE_SIZE = 50;
    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of(
            "updatedAt", "createdAt", "priceSimple", "priceMatrimonial"
    );

    private final PensionRepository repo;
    private final PublicPensionDistanceRepository distanceSearch;
    private final PensionPromotionRepository promotions;
    private final AppProperties appProperties;
    private final PensionDetailService details;
    private final PensionCatalogQualityService catalogQuality;
    private final StudyCenterCatalogService studyCenterCatalog;
    private final PublicTrafficProtectionService traffic;

    public PublicPensionController(
            PensionRepository repo,
            PublicPensionDistanceRepository distanceSearch,
            PensionPromotionRepository promotions,
            AppProperties appProperties,
            PensionDetailService details,
            PensionCatalogQualityService catalogQuality,
            StudyCenterCatalogService studyCenterCatalog,
            PublicTrafficProtectionService traffic
    ) {
        this.repo = repo;
        this.distanceSearch = distanceSearch;
        this.promotions = promotions;
        this.appProperties = appProperties;
        this.details = details;
        this.catalogQuality = catalogQuality;
        this.studyCenterCatalog = studyCenterCatalog;
        this.traffic = traffic;
    }

    @GetMapping
    public Page<PensionCardDto> search(
            HttpServletRequest servletRequest,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String countryCode,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String neighborhood,
            @RequestParam(required = false) PensionSpecs.RoomType roomType,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(required = false) AdmissionType admissionType,
            @RequestParam(required = false) ResidentProfile residentProfile,
            @RequestParam(required = false) String studyCenter,
            @RequestParam(required = false) Double maxStudyDistanceKm,
            @RequestParam(required = false) BathroomType bathroomType,
            @RequestParam(required = false) Boolean hasParking,
            @RequestParam(required = false) Set<Amenity> amenities,
            @RequestParam(required = false) Double south,
            @RequestParam(required = false) Double west,
            @RequestParam(required = false) Double north,
            @RequestParam(required = false) Double east,
            @RequestParam(required = false) Double nearLat,
            @RequestParam(required = false) Double nearLng,
            @RequestParam(required = false) Double nearRadiusKm,
            @RequestParam(required = false) java.util.List<String> qTerms,
            @RequestParam(required = false) java.util.List<String> areaPoint,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "updatedAt,desc") String sort
    ) {
        traffic.checkSearch(servletRequest, false);
        validateSearchShape(q, countryCode, city, neighborhood, studyCenter, qTerms, areaPoint, page, size, minPrice, maxPrice);
        validateBounds(south, west, north, east);
        validateNear(nearLat, nearLng, nearRadiusKm);
        validateSearchComplexity(q, qTerms, areaPoint, nearRadiusKm, maxStudyDistanceKm,
                south, west, north, east, amenities, false, sort);

        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PUBLIC_PAGE_SIZE);

        var centerLocation = resolveStudyCenterLocation(studyCenter, maxStudyDistanceKm);
        var spec = buildSearchSpec(
                q, countryCode, city, neighborhood, roomType,
                minPrice, maxPrice, admissionType, residentProfile, studyCenter, maxStudyDistanceKm, centerLocation,
                bathroomType, hasParking, amenities, south, west, north, east,
                nearLat, nearLng, nearRadiusKm
        );
        spec = spec
                .and(uy.pensiones.pension.PensionSearchExtensions.freeTextTerms(qTerms))
                .and(uy.pensiones.pension.PensionSearchExtensions.insidePolygon(areaPoint));

        PromotionContext promotionContext = promotionContext(studyCenter, centerLocation);

        Page<Pension> result;
        if (isDistanceSort(sort)) {
            requireNearForDistanceSort(nearLat, nearLng, nearRadiusKm);
            result = distanceSearch.findPage(
                    spec,
                    PageRequest.of(safePage, safeSize),
                    nearLat,
                    nearLng,
                    promotionContext.now(),
                    promotionContext.studyCenterId()
            );
        } else {
            Pageable pageable = PageRequest.of(safePage, safeSize);
            result = distanceSearch.findPage(
                    spec,
                    pageable,
                    parseSort(sort),
                    promotionContext.now(),
                    promotionContext.studyCenterId()
            );
        }

        Map<Long, Long> promotionByPension = effectivePromotionByPension(result.getContent(), promotionContext);
        return result.map(item -> toCard(item, promotionByPension.get(item.getId())));
    }

    /**
     * Resultados para pintar el mapa. Usa los mismos filtros de la lista, pero no
     * queda limitado por la paginación visible. Se acota a MAP_LIMIT para evitar
     * respuestas excesivas si el catálogo crece mucho.
     */
    @GetMapping("/map")
    public PensionMapResultDto searchMap(
            HttpServletRequest servletRequest,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String countryCode,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String neighborhood,
            @RequestParam(required = false) PensionSpecs.RoomType roomType,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(required = false) AdmissionType admissionType,
            @RequestParam(required = false) ResidentProfile residentProfile,
            @RequestParam(required = false) String studyCenter,
            @RequestParam(required = false) Double maxStudyDistanceKm,
            @RequestParam(required = false) BathroomType bathroomType,
            @RequestParam(required = false) Boolean hasParking,
            @RequestParam(required = false) Set<Amenity> amenities,
            @RequestParam(required = false) Double south,
            @RequestParam(required = false) Double west,
            @RequestParam(required = false) Double north,
            @RequestParam(required = false) Double east,
            @RequestParam(required = false) Double nearLat,
            @RequestParam(required = false) Double nearLng,
            @RequestParam(required = false) Double nearRadiusKm,
            @RequestParam(required = false) java.util.List<String> qTerms,
            @RequestParam(required = false) java.util.List<String> areaPoint
    ) {
        traffic.checkSearch(servletRequest, true);
        validateSearchShape(q, countryCode, city, neighborhood, studyCenter, qTerms, areaPoint, 0, MAX_PUBLIC_PAGE_SIZE, minPrice, maxPrice);
        validateBounds(south, west, north, east);
        validateNear(nearLat, nearLng, nearRadiusKm);
        validateSearchComplexity(q, qTerms, areaPoint, nearRadiusKm, maxStudyDistanceKm,
                south, west, north, east, amenities, true, null);

        var centerLocation = resolveStudyCenterLocation(studyCenter, maxStudyDistanceKm);
        var spec = buildSearchSpec(
                q, countryCode, city, neighborhood, roomType,
                minPrice, maxPrice, admissionType, residentProfile, studyCenter, maxStudyDistanceKm, centerLocation,
                bathroomType, hasParking, amenities, south, west, north, east,
                nearLat, nearLng, nearRadiusKm
        )
                .and(uy.pensiones.pension.PensionSearchExtensions.freeTextTerms(qTerms))
                .and(uy.pensiones.pension.PensionSearchExtensions.insidePolygon(areaPoint))
                .and(PensionSpecs.hasCoordinates());

        PromotionContext promotionContext = promotionContext(studyCenter, centerLocation);
        PublicPensionDistanceRepository.DistanceResult result;

        if (nearLat != null && nearLng != null && nearRadiusKm != null) {
            result = distanceSearch.findTop(
                    spec,
                    MAP_LIMIT,
                    nearLat,
                    nearLng,
                    promotionContext.now(),
                    promotionContext.studyCenterId()
            );
        } else {
            result = distanceSearch.findTop(
                    spec,
                    MAP_LIMIT,
                    new Sort.Order(Sort.Direction.DESC, "updatedAt"),
                    promotionContext.now(),
                    promotionContext.studyCenterId()
            );
        }

        Map<Long, Long> promotionByPension = effectivePromotionByPension(result.content(), promotionContext);
        return new PensionMapResultDto(
                result.content().stream()
                        .map(item -> toCard(item, promotionByPension.get(item.getId())))
                        .toList(),
                result.totalElements(),
                result.truncated()
        );
    }

    @GetMapping("/study-centers")
    public List<String> studyCenters(HttpServletRequest servletRequest, @RequestParam(required = false) String q) {
        traffic.checkCatalog(servletRequest);
        requireMaxLength("texto de centro de estudio", q, 180);
        String needle = q == null ? "" : q.trim();
        return repo.findPublicStudyCenters(needle, catalogQuality.publicAvailabilityCutoff(), 50);
    }

    @GetMapping("/study-centers/catalog")
    public List<StudyCenterCatalogDTO> studyCenterCatalog(HttpServletRequest servletRequest, @RequestParam(required = false) String q) {
        traffic.checkCatalog(servletRequest);
        requireMaxLength("texto de centro de estudio", q, 180);
        String needle = q == null ? "" : q.trim();
        List<String> publicNames = repo.findPublicStudyCenters(needle, catalogQuality.publicAvailabilityCutoff(), 100);
        return studyCenterCatalog.resolvePublicCatalog(publicNames, needle, 100);
    }

    @GetMapping(value = "/{id}/share", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> share(HttpServletRequest servletRequest, @PathVariable Long id) {
        traffic.checkDetail(servletRequest);
        Pension p = findPublished(id);

        String frontendRoot = trimTrailingSlash(appProperties.getFrontendUrl());
        String canonicalUrl = frontendRoot + "/pensions/" + p.getId();
        String backendRoot = trimTrailingSlash(
                ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString()
        );
        String imagePath = featuredImageUrl(p);
        String imageUrl = imagePath == null ? null : backendRoot + imagePath;

        String title = p.getName() + locationSuffix(p) + " | Pensiones";
        String description = shareDescription(p);

        StringBuilder html = new StringBuilder(2048);
        html.append("<!doctype html><html lang=\"es\"><head>")
                .append("<meta charset=\"UTF-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<title>").append(escapeHtml(title)).append("</title>")
                .append(meta("description", description))
                .append("<meta name=\"robots\" content=\"noindex,follow\">")
                .append(propertyMeta("og:type", "website"))
                .append(propertyMeta("og:site_name", "Pensiones"))
                .append(propertyMeta("og:locale", "es_UY"))
                .append(propertyMeta("og:title", title))
                .append(propertyMeta("og:description", description))
                .append(propertyMeta("og:url", canonicalUrl))
                .append(meta("twitter:card", imageUrl == null ? "summary" : "summary_large_image"))
                .append(meta("twitter:title", title))
                .append(meta("twitter:description", description));

        if (imageUrl != null) {
            html.append(propertyMeta("og:image", imageUrl))
                    .append(propertyMeta("og:image:alt", "Foto de " + p.getName()))
                    .append(meta("twitter:image", imageUrl));
        }

        html.append("<link rel=\"canonical\" href=\"")
                .append(escapeHtml(canonicalUrl))
                .append("\">")
                .append("<meta http-equiv=\"refresh\" content=\"0;url=")
                .append(escapeHtml(canonicalUrl))
                .append("\">")
                .append("</head><body>")
                .append("<p>Abriendo <a href=\"")
                .append(escapeHtml(canonicalUrl))
                .append("\">")
                .append(escapeHtml(p.getName()))
                .append("</a>...</p>")
                .append("</body></html>");

        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
                .header("X-Robots-Tag", "noindex, follow")
                .contentType(MediaType.TEXT_HTML)
                .body(html.toString());
    }

    @GetMapping("/{id}")
    public PublicPensionDetailDto detail(HttpServletRequest servletRequest, @PathVariable Long id) {
        traffic.checkDetail(servletRequest);
        return details.toPublicDetail(findPublished(id));
    }

    private void validateSearchShape(String q, String countryCode, String city, String neighborhood,
                                     String studyCenter, java.util.List<String> qTerms,
                                     java.util.List<String> areaPoint, int page, int size,
                                     BigDecimal minPrice, BigDecimal maxPrice) {
        requireMaxLength("texto de búsqueda", q, 160);
        requireMaxLength("país", countryCode, 8);
        requireMaxLength("ciudad", city, 120);
        requireMaxLength("barrio", neighborhood, 120);
        requireMaxLength("centro de estudio", studyCenter, 180);

        if (page < 0 || page > MAX_PUBLIC_PAGE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La página solicitada está fuera del rango permitido.");
        }
        if (size < 1 || size > MAX_PUBLIC_PAGE_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El tamaño de página debe estar entre 1 y " + MAX_PUBLIC_PAGE_SIZE + ".");
        }
        if (qTerms != null) {
            if (qTerms.size() > 10) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Puedes combinar como máximo 10 términos de búsqueda.");
            }
            qTerms.forEach(term -> requireMaxLength("término de búsqueda", term, 80));
        }
        if (areaPoint != null) {
            if (!areaPoint.isEmpty() && (areaPoint.size() < 3 || areaPoint.size() > 50)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El área dibujada debe contener entre 3 y 50 puntos.");
            }
            areaPoint.forEach(point -> requireMaxLength("punto del área", point, 64));
        }
        if ((minPrice != null && minPrice.signum() < 0) || (maxPrice != null && maxPrice.signum() < 0)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Los precios de búsqueda no pueden ser negativos.");
        }
        if (minPrice != null && maxPrice != null && minPrice.compareTo(maxPrice) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El precio mínimo no puede ser mayor que el máximo.");
        }
    }

    private void validateSearchComplexity(String q, java.util.List<String> qTerms,
                                          java.util.List<String> areaPoint, Double nearRadiusKm,
                                          Double maxStudyDistanceKm, Double south, Double west,
                                          Double north, Double east, Set<Amenity> amenities,
                                          boolean map, String sort) {
        int score = map ? 1 : 0;
        if (q != null && !q.isBlank()) score += 2;
        if (qTerms != null) {
            score += (int) qTerms.stream().filter(term -> term != null && !term.isBlank()).count();
        }
        if (areaPoint != null && !areaPoint.isEmpty()) {
            score += 2 + Math.min(5, (areaPoint.size() + 9) / 10);
        }
        if (nearRadiusKm != null) score += 3;
        if (maxStudyDistanceKm != null) score += 3;
        if (south != null && west != null && north != null && east != null) score += 1;
        if (amenities != null && !amenities.isEmpty()) score += Math.min(2, (amenities.size() + 3) / 4);
        if (isDistanceSort(sort)) score += 2;

        // El umbral admite holgadamente los flujos del portal (área dibujada +
        // 2/3 términos + distancia), pero corta combinaciones construidas para
        // multiplicar LIKE + trigonometría + polígono en una misma consulta.
        if (score > 18) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La combinación de filtros es demasiado compleja. Reduce términos, área o filtros de distancia e intenta nuevamente.");
        }
    }

    private void requireMaxLength(String field, String value, int max) {
        if (value != null && value.length() > max) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El " + field + " supera el largo máximo permitido.");
        }
    }

    private org.springframework.data.jpa.domain.Specification<Pension> buildSearchSpec(
            String q,
            String countryCode,
            String city,
            String neighborhood,
            PensionSpecs.RoomType roomType,
            BigDecimal minPrice,
            BigDecimal maxPrice,
            AdmissionType admissionType,
            ResidentProfile residentProfile,
            String studyCenter,
            Double maxStudyDistanceKm,
            StudyCenterLocation centerLocation,
            BathroomType bathroomType,
            Boolean hasParking,
            Set<Amenity> amenities,
            Double south,
            Double west,
            Double north,
            Double east,
            Double nearLat,
            Double nearLng,
            Double nearRadiusKm
    ) {
        return PensionSpecs.publicSearch(
                q, countryCode, city, neighborhood,
                roomType == null ? PensionSpecs.RoomType.ANY : roomType,
                minPrice, maxPrice,
                bathroomType, admissionType, residentProfile, studyCenter,
                centerLocation == null ? null : centerLocation.lat(),
                centerLocation == null ? null : centerLocation.lng(),
                maxStudyDistanceKm,
                hasParking, amenities, south, west, north, east,
                nearLat, nearLng, nearRadiusKm,
                catalogQuality.publicAvailabilityCutoff()
        );
    }

    private record StudyCenterLocation(Long id, double lat, double lng) {}

    private StudyCenterLocation resolveStudyCenterLocation(String studyCenter, Double maxDistanceKm) {
        if (maxDistanceKm == null) return null;
        if (maxDistanceKm < 0.1d || maxDistanceKm > 50d) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La distancia máxima al centro de estudio debe estar entre 0,1 y 50 km.");
        }
        if (studyCenter == null || studyCenter.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Selecciona un centro de estudio para buscar por distancia.");
        }

        var center = studyCenterCatalog.findByName(studyCenter)
                .filter(item -> Boolean.TRUE.equals(item.getVerified()))
                .filter(item -> item.getLat() != null && item.getLng() != null)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Ese centro de estudio todavía no tiene una ubicación georreferenciada y verificada."));
        return new StudyCenterLocation(center.getId(), center.getLat(), center.getLng());
    }

    private void validateBounds(Double south, Double west, Double north, Double east) {
        int provided = 0;
        if (south != null) provided++;
        if (west != null) provided++;
        if (north != null) provided++;
        if (east != null) provided++;

        if (provided == 0) return;
        if (provided != 4) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Para buscar por zona deben enviarse south, west, north y east"
            );
        }

        if (south < -90 || south > 90 || north < -90 || north > 90
                || west < -180 || west > 180 || east < -180 || east > 180
                || south >= north || west >= east) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Límites de mapa inválidos");
        }
    }

    private void validateNear(Double lat, Double lng, Double radiusKm) {
        int provided = 0;
        if (lat != null) provided++;
        if (lng != null) provided++;
        if (radiusKm != null) provided++;
        if (provided == 0) return;
        if (provided != 3) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Para buscar cerca de una ubicación deben enviarse nearLat, nearLng y nearRadiusKm.");
        }
        if (lat < -90d || lat > 90d || lng < -180d || lng > 180d
                || radiusKm < 0.5d || radiusKm > 50d) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La ubicación o el radio de búsqueda son inválidos. El radio debe estar entre 0,5 y 50 km.");
        }
    }

    private boolean isDistanceSort(String sort) {
        if (sort == null) return false;
        var parts = sort.split(",");
        return parts.length > 0 && "distance".equalsIgnoreCase(parts[0].trim());
    }

    private void requireNearForDistanceSort(Double lat, Double lng, Double radiusKm) {
        if (lat == null || lng == null || radiusKm == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Para ordenar por distancia primero selecciona una ubicación de referencia.");
        }
    }

    private Sort.Order parseSort(String sort) {
        var parts = sort == null ? new String[0] : sort.split(",");
        var requestedField = parts.length > 0 ? parts[0] : "updatedAt";
        var field = ALLOWED_SORT_FIELDS.contains(requestedField) ? requestedField : "updatedAt";
        var dir = (parts.length > 1 && "asc".equalsIgnoreCase(parts[1]))
                ? Sort.Direction.ASC
                : Sort.Direction.DESC;
        return new Sort.Order(dir, field);
    }

    private Pension findPublished(Long id) {
        return repo.findWithOwnerById(id)
                .filter(catalogQuality::isPubliclyVisible)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Pensión no encontrada"
                ));
    }

    private String locationSuffix(Pension p) {
        if (p.getCity() != null && !p.getCity().isBlank()) {
            return " en " + p.getCity().trim();
        }
        return "";
    }

    private String shareDescription(Pension p) {
        String raw = p.getDescription();
        if (raw == null || raw.isBlank()) {
            String place = p.getCity() == null || p.getCity().isBlank()
                    ? "Uruguay"
                    : p.getCity().trim();
            raw = "Consulta habitaciones, precios y disponibilidad de " + p.getName() + " en " + place + ".";
        }

        String admission = "";
        if (p.getAdmissionType() != null) {
            admission = switch (p.getAdmissionType()) {
                case WOMEN_ONLY -> "Solo mujeres. ";
                case MEN_ONLY -> "Solo hombres. ";
                case MIXED -> "Residencia mixta. ";
            };
        }

        String residentProfile = "";
        if (p.getResidentProfile() != null) {
            residentProfile = switch (p.getResidentProfile()) {
                case STUDENTS_ONLY -> "Solo estudiantes. ";
                case STUDENTS_PREFERRED -> "Preferentemente estudiantes. ";
                case OPEN_TO_ALL -> "Sin restricción de perfil. ";
            };
        }

        String nearbyStudy = p.getStudyCenters() == null || p.getStudyCenters().isEmpty()
                ? ""
                : "Cerca de " + p.getStudyCenters().stream()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .limit(2)
                .reduce((a, b) -> a + " y " + b)
                .orElse("") + ". ";

        String compact = (admission + residentProfile + nearbyStudy + raw).replaceAll("\\s+", " ").trim();
        if (compact.length() <= 180) return compact;
        return compact.substring(0, 177).trim() + "...";
    }

    private String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) return "http://localhost:5173";
        return value.replaceAll("/+$", "");
    }

    private String meta(String name, String content) {
        return "<meta name=\"" + escapeHtml(name) + "\" content=\""
                + escapeHtml(content) + "\">";
    }

    private String propertyMeta(String property, String content) {
        return "<meta property=\"" + escapeHtml(property) + "\" content=\""
                + escapeHtml(content) + "\">";
    }

    private String escapeHtml(String value) {
        if (value == null) return "";
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private String featuredImageUrl(Pension p) {
        return (p.getFeaturedImage() == null || p.getFeaturedImage().isBlank())
                ? null
                : PensionImageVariantService.largeUrl(p);
    }

    private record PromotionContext(OffsetDateTime now, Long studyCenterId) {}

    private PromotionContext promotionContext(String studyCenter, StudyCenterLocation resolvedCenter) {
        Long studyCenterId = resolvedCenter == null ? null : resolvedCenter.id();
        if (studyCenterId == null && studyCenter != null && !studyCenter.isBlank()) {
            studyCenterId = studyCenterCatalog.findByName(studyCenter.trim())
                    .map(uy.pensiones.model.StudyCenterCatalog::getId)
                    .orElse(null);
        }
        return new PromotionContext(OffsetDateTime.now(ZoneOffset.UTC), studyCenterId);
    }

    private Map<Long, Long> effectivePromotionByPension(List<Pension> pensions, PromotionContext context) {
        List<Long> pensionIds = pensions == null ? List.of() : pensions.stream()
                .map(Pension::getId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (pensionIds.isEmpty()) return Map.of();

        List<PensionPromotionRepository.PublicPromotionExposureRow> rows = context.studyCenterId() == null
                ? promotions.findEffectiveGlobalExposuresForPensions(context.now(), pensionIds)
                : promotions.findEffectiveExposuresForStudyCenterAndPensions(
                        context.now(), context.studyCenterId(), pensionIds);

        Map<Long, Long> result = new LinkedHashMap<>();
        for (var row : rows) {
            if (row.getPensionId() == null || row.getPromotionId() == null) continue;
            // La query coloca primero el target STUDY_CENTER específico y luego GLOBAL.
            result.putIfAbsent(row.getPensionId(), row.getPromotionId());
        }
        return result;
    }

    private PensionCardDto toCard(Pension p, Long featuredPromotionId) {
        String img = PensionImageVariantService.cardUrl(p);
        boolean effectiveFeatured = Boolean.TRUE.equals(p.getFeatured()) || featuredPromotionId != null;

        return new PensionCardDto(
                p.getId(),
                p.getName(),
                p.getCity(),
                p.getNeighborhood(),
                p.getCountryCode(),
                PublicLocationPrivacy.approximateCoordinate(p.getLat()),
                PublicLocationPrivacy.approximateCoordinate(p.getLng()),
                p.getAvailableSimple(),
                p.getAvailableMatrimonial(),
                p.getPriceSimple(),
                p.getPriceMatrimonial(),
                p.getAdmissionType(),
                p.getResidentProfile(),
                p.getStudyCenters(),
                p.getBathroomsCount(),
                p.getBathroomType(),
                p.getHasParking(),
                p.getAmenities(),
                effectiveFeatured,
                featuredPromotionId,
                img,
                p.getAvailabilityUpdatedAt()
        );
    }
}
