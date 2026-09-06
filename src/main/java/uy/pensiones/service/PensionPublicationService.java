package uy.pensiones.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import uy.pensiones.enums.PensionStatus;
import uy.pensiones.enums.ResidentProfile;
import uy.pensiones.model.Pension;
import uy.pensiones.model.PensionMedia;
import uy.pensiones.repo.PensionMediaRepository;
import uy.pensiones.web.dto.PensionPublicationReportDTO;
import uy.pensiones.web.dto.PensionQualityItemDTO;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
public class PensionPublicationService {

    private final PensionMediaRepository media;
    private final PensionCatalogQualityService catalogQuality;

    public PensionPublicationService(PensionMediaRepository media, PensionCatalogQualityService catalogQuality) {
        this.media = media;
        this.catalogQuality = catalogQuality;
    }

    /**
     * Devuelve tanto los requisitos estrictos de publicación como recomendaciones
     * de calidad. El porcentaje de completitud incluye ambos grupos; publishable
     * depende exclusivamente de los requisitos obligatorios.
     */
    public PensionPublicationReportDTO report(Pension pension) {
        List<PensionMedia> mediaItems = media.findByPensionIdOrderBySortOrderAscIdAsc(pension.getId());
        long imageCount = mediaItems.stream().filter(m -> m.getKind() == PensionMedia.Kind.IMAGE).count();
        boolean hasVideo = mediaItems.stream().anyMatch(m ->
                m.getKind() == PensionMedia.Kind.VIDEO || m.getKind() == PensionMedia.Kind.YOUTUBE);
        boolean validCover = !blank(pension.getFeaturedImage()) && mediaItems.stream().anyMatch(m ->
                m.getKind() == PensionMedia.Kind.IMAGE
                        && Objects.equals(m.getFilename(), pension.getFeaturedImage()));

        int simple = safe(pension.getCapacitySimple());
        int matrimonial = safe(pension.getCapacityMatrimonial());

        List<PensionQualityItemDTO> items = new ArrayList<>();

        // Requisitos mínimos para hacer visible la ficha.
        items.add(item("name", "Nombre de la pensión", true,
                !blank(pension.getName()), "Agrega un nombre claro y reconocible."));
        items.add(item("description", "Descripción", true,
                !blank(pension.getDescription()), "Explica qué ofrece la pensión y a quién está dirigida."));
        items.add(item("country", "País", true,
                !blank(pension.getCountryCode()), "Selecciona el país."));
        items.add(item("city", "Ciudad", true,
                !blank(pension.getCity()), "Indica la ciudad."));
        items.add(item("address", "Dirección", true,
                !blank(pension.getAddressLine1()), "Indica la dirección de la pensión."));
        items.add(item("coordinates", "Ubicación en el mapa", true,
                validCoordinates(pension.getLat(), pension.getLng()), "Marca una ubicación válida en el mapa."));
        items.add(item("admissionType", "Tipo de admisión", true,
                pension.getAdmissionType() != null, "Indica si la residencia es mixta, solo para mujeres o solo para hombres."));
        items.add(item("residentProfile", "Perfil de residentes", true,
                pension.getResidentProfile() != null, "Indica si admite a cualquier persona o si está orientada a estudiantes."));
        items.add(item("rooms", "Capacidad de habitaciones", true,
                simple > 0 || matrimonial > 0, "Configura al menos un tipo de habitación."));
        items.add(item("simplePrice", "Precio de habitación simple", true,
                simple <= 0 || positive(pension.getPriceSimple()),
                "Si ofreces habitaciones simples, indica un precio mayor que 0."));
        items.add(item("matrimonialPrice", "Precio de habitación matrimonial", true,
                matrimonial <= 0 || positive(pension.getPriceMatrimonial()),
                "Si ofreces habitaciones matrimoniales, indica un precio mayor que 0."));
        items.add(item("image", "Al menos una foto", true,
                imageCount > 0, "Sube al menos una imagen real de la pensión."));
        items.add(item("cover", "Foto de portada", true,
                validCover, "Selecciona una imagen existente como portada."));
        items.add(item("moderation", "Revisión de moderación", true,
                !Boolean.TRUE.equals(pension.getModerationBlocked()),
                "El anuncio está pausado por moderación y requiere revisión administrativa."));

        // Recomendaciones: no bloquean publicación, pero elevan la calidad del anuncio.
        items.add(item("descriptionQuality", "Descripción detallada", false,
                textLength(pension.getDescription()) >= 120,
                "Una descripción de al menos 120 caracteres ayuda a responder dudas antes del contacto."));
        items.add(item("neighborhood", "Barrio o zona", false,
                !blank(pension.getNeighborhood()), "Agregar el barrio mejora la búsqueda y la ubicación."));
        items.add(item("bathrooms", "Información de baños", false,
                safe(pension.getBathroomsCount()) > 0 && pension.getBathroomType() != null,
                "Indica cantidad y tipo de baño."));
        items.add(item("amenities", "Amenities completos", false,
                pension.getAmenities() != null && pension.getAmenities().size() >= 3,
                "Marca al menos tres servicios relevantes, si están disponibles."));
        items.add(item("nearby", "Puntos cercanos", false,
                pension.getNearbyTags() != null && !pension.getNearbyTags().isEmpty(),
                "Agrega transporte, comercios u otros puntos útiles cercanos."));
        if (pension.getResidentProfile() != null && pension.getResidentProfile() != ResidentProfile.OPEN_TO_ALL) {
            items.add(item("studyCenters", "Centros de estudio cercanos", false,
                    pension.getStudyCenters() != null && !pension.getStudyCenters().isEmpty(),
                    "Agrega al menos una facultad, universidad o centro de estudio cercano."));
        }
        items.add(item("gallery", "Galería con varias fotos", false,
                imageCount >= 3, "Tres o más fotos ayudan a entender mejor el alojamiento."));
        items.add(item("video", "Video de la pensión", false,
                hasVideo, "Un video es opcional, pero mejora la confianza del anuncio."));
        items.add(item("directContact", "Contacto directo", false,
                hasVisibleDirectContact(pension),
                "Habilita teléfono o WhatsApp si quieres ofrecer una vía de contacto inmediata."));
        items.add(item("possibleDuplicate", "Posible publicación duplicada", false,
                !catalogQuality.hasPotentialDuplicate(pension),
                "Ya tienes otra publicación en la misma dirección. Si se trata del mismo alojamiento, edita la existente en vez de crear una copia."));

        List<String> issues = requiredIssues(pension, imageCount, validCover);
        int completed = (int) items.stream().filter(PensionQualityItemDTO::complete).count();
        int total = items.size();
        int completeness = total == 0 ? 100 : (int) Math.round((completed * 100.0) / total);

        return new PensionPublicationReportDTO(
                issues.isEmpty(),
                completeness,
                completed,
                total,
                List.copyOf(issues),
                List.copyOf(items)
        );
    }

    public List<String> validationIssues(Pension pension) {
        return report(pension).issues();
    }

    public void requirePublishable(Pension pension) {
        PensionPublicationReportDTO report = report(pension);
        if (!report.publishable()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "No se puede publicar: " + String.join(" ", report.issues())
            );
        }
    }

    public void requirePublishedEditStillValid(Pension pension) {
        if (pension.getStatus() == PensionStatus.PUBLISHED) {
            requirePublishable(pension);
        }
    }

    public void requireCanDeleteMedia(Pension pension, PensionMedia target) {
        if (pension.getStatus() != PensionStatus.PUBLISHED || target.getKind() != PensionMedia.Kind.IMAGE) {
            return;
        }

        long imageCount = media.countByPensionIdAndKind(pension.getId(), PensionMedia.Kind.IMAGE);
        if (imageCount <= 1) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Una pensión publicada debe conservar al menos una imagen. Pausa la publicación antes de eliminarla."
            );
        }
    }

    private List<String> requiredIssues(Pension pension, long imageCount, boolean validCover) {
        List<String> issues = new ArrayList<>();

        if (Boolean.TRUE.equals(pension.getModerationBlocked())) {
            issues.add("El anuncio está bloqueado temporalmente por moderación. Un administrador debe levantar la revisión antes de republicarlo.");
        }

        if (blank(pension.getName())) issues.add("Agrega el nombre de la pensión.");
        if (blank(pension.getDescription())) issues.add("Agrega una descripción.");
        if (blank(pension.getCountryCode())) issues.add("Selecciona el país.");
        if (blank(pension.getCity())) issues.add("Indica la ciudad.");
        if (blank(pension.getAddressLine1())) issues.add("Indica la dirección.");

        if (!validCoordinates(pension.getLat(), pension.getLng())) {
            issues.add("Selecciona una ubicación válida en el mapa.");
        }

        if (pension.getAdmissionType() == null) {
            issues.add("Indica si la residencia es mixta, solo para mujeres o solo para hombres.");
        }
        if (pension.getResidentProfile() == null) {
            issues.add("Indica si la residencia admite a cualquier persona, prefiere estudiantes o es solo para estudiantes.");
        }

        int simple = safe(pension.getCapacitySimple());
        int matrimonial = safe(pension.getCapacityMatrimonial());
        if (simple <= 0 && matrimonial <= 0) {
            issues.add("Indica capacidad para al menos un tipo de habitación.");
        }
        if (simple > 0 && !positive(pension.getPriceSimple())) {
            issues.add("Indica un precio mayor que 0 para la habitación simple.");
        }
        if (matrimonial > 0 && !positive(pension.getPriceMatrimonial())) {
            issues.add("Indica un precio mayor que 0 para la habitación matrimonial.");
        }

        if (imageCount <= 0) {
            issues.add("Sube al menos una imagen de la pensión.");
        } else if (blank(pension.getFeaturedImage())) {
            issues.add("Marca una imagen como portada.");
        } else if (!validCover) {
            issues.add("La imagen de portada ya no existe. Selecciona otra portada.");
        }

        return issues;
    }

    private PensionQualityItemDTO item(String key, String label, boolean required, boolean complete, String hint) {
        return new PensionQualityItemDTO(key, label, required, complete, hint);
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private int textLength(String value) {
        return value == null ? 0 : value.trim().length();
    }

    private int safe(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }

    private boolean positive(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0;
    }

    private boolean hasVisibleDirectContact(Pension pension) {
        return (Boolean.TRUE.equals(pension.getShowPhone()) && !blank(pension.getContactPhone()))
                || (Boolean.TRUE.equals(pension.getShowWhatsapp())
                && PublicContactNumbers.normalizeWhatsapp(pension.getContactWhatsapp()) != null);
    }

    private boolean validCoordinates(Double lat, Double lng) {
        return lat != null && lng != null
                && Double.isFinite(lat) && Double.isFinite(lng)
                && lat >= -90 && lat <= 90
                && lng >= -180 && lng <= 180;
    }
}
