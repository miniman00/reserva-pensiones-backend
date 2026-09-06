package uy.pensiones.web;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;
import uy.pensiones.enums.AdmissionType;
import uy.pensiones.enums.Amenity;
import uy.pensiones.enums.BathroomType;
import uy.pensiones.enums.PensionStatus;
import uy.pensiones.enums.ResidentProfile;
import uy.pensiones.model.User;
import uy.pensiones.repo.PensionRepository;
import uy.pensiones.repo.UserRepository;
import uy.pensiones.model.*;
import uy.pensiones.security.Authz;
import uy.pensiones.service.PensionService;
import uy.pensiones.service.PensionPublicationService;
import uy.pensiones.service.PensionDetailService;
import uy.pensiones.web.dto.ChangeOwnerRequest;
import uy.pensiones.web.dto.PensionPreviewDTO;
import uy.pensiones.web.dto.PensionPublicationReportDTO;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@RestController
@RequestMapping("/api/pensions")
public class PensionController {

    public record PensionDTO(
            Long id, Long orgId, String name, String description,
            String countryCode, String addressLine1, String city, String state,
            String postalCode, String neighborhood, Double lat, Double lng,
            Integer capacitySimple, Integer capacityMatrimonial,
            Integer availableSimple, Integer availableMatrimonial,
            BigDecimal priceSimple, BigDecimal priceMatrimonial,
            AdmissionType admissionType, ResidentProfile residentProfile,
            Integer bathroomsCount, BathroomType bathroomType, Boolean hasParking,
            String contactName, String contactPhone, String contactWhatsapp,
            Boolean showPhone, Boolean showWhatsapp,
            Set<Amenity> amenities, Set<String> nearbyTags, Set<String> studyCenters,
            String featuredImage, PensionStatus status, Boolean moderationBlocked, OffsetDateTime availabilityUpdatedAt,
            String draftStep, Long version,
            Boolean canEdit, Boolean canManageMembers, Boolean canUpdateAvailability
    ) {
        public static PensionDTO of(Pension p,
                                    boolean canEdit,
                                    boolean canManageMembers,
                                    boolean canUpdateAvailability) {
            return new PensionDTO(
                    p.getId(), p.getOrg().getId(), p.getName(), p.getDescription(),
                    p.getCountryCode(), p.getAddressLine1(), p.getCity(), p.getState(),
                    p.getPostalCode(), p.getNeighborhood(), p.getLat(), p.getLng(),
                    p.getCapacitySimple(), p.getCapacityMatrimonial(),
                    p.getAvailableSimple(), p.getAvailableMatrimonial(),
                    p.getPriceSimple(), p.getPriceMatrimonial(),
                    p.getAdmissionType(), p.getResidentProfile(),
                    p.getBathroomsCount(), p.getBathroomType(), p.getHasParking(),
                    p.getContactName(), p.getContactPhone(), p.getContactWhatsapp(),
                    Boolean.TRUE.equals(p.getShowPhone()), Boolean.TRUE.equals(p.getShowWhatsapp()),
                    p.getAmenities() == null ? Set.of() : Set.copyOf(p.getAmenities()),
                    p.getNearbyTags() == null ? Set.of() : Set.copyOf(p.getNearbyTags()),
                    p.getStudyCenters() == null ? Set.of() : Set.copyOf(p.getStudyCenters()),
                    p.getFeaturedImage(), p.getStatus(), Boolean.TRUE.equals(p.getModerationBlocked()), p.getAvailabilityUpdatedAt(),
                    p.getDraftStep(), p.getVersion(),
                    canEdit, canManageMembers, canUpdateAvailability
            );
        }
    }

    public record AvailabilityDTO(Integer availableSimple, Integer availableMatrimonial) {}
    public record PublicationRequest(PensionStatus status, Long version) {}
    public record DraftStepRequest(String step) {}

    private final PensionService service;
    private final PensionRepository repo;
    private final UserRepository users;
    private final Authz authz;
    private final PensionPublicationService publication;
    private final PensionDetailService details;

    public PensionController(PensionService service, PensionRepository repo, UserRepository users,
                             Authz authz, PensionPublicationService publication, PensionDetailService details) {
        this.service = service; this.repo = repo; this.users = users; this.authz = authz;
        this.publication = publication; this.details = details;
    }

    private User current(@AuthenticationPrincipal OAuth2User p) {
        if (p == null) return null;
        User u = (User) p.getAttribute("appUser");
        if (u != null) return users.findById(u.getId()).orElse(null);
        String email = p.getAttribute("email");
        return email == null ? null : users.findByEmail(email).orElse(null);
    }

    private PensionDTO dto(Pension p, OAuth2User principal) {
        return PensionDTO.of(
                p,
                authz.canEditPensionFields(principal, p.getId()),
                authz.canManageMembers(principal, p.getId()),
                authz.canUpdateAvailability(principal, p.getId())
        );
    }

    @GetMapping("/mine")
    public List<PensionDTO> mine(@AuthenticationPrincipal OAuth2User principal) {
        User me = current(principal);
        return service.myPensions(me.getId()).stream().map(p -> dto(p, principal)).toList();
    }

    @PostMapping
    public PensionDTO create(@AuthenticationPrincipal OAuth2User principal,
                             @RequestBody PensionDTO req) {
        User me = current(principal);
        Pension p = new Pension();
        // campos principales
        p.setName(req.name());
        p.setDescription(req.description());
        p.setCountryCode(req.countryCode());
        p.setAddressLine1(req.addressLine1());
        p.setCity(req.city());
        p.setState(req.state());
        p.setPostalCode(req.postalCode());
        p.setNeighborhood(req.neighborhood());
        p.setLat(req.lat());
        p.setLng(req.lng());
        p.setCapacitySimple(req.capacitySimple());
        p.setCapacityMatrimonial(req.capacityMatrimonial());
        p.setAvailableSimple(req.availableSimple());
        p.setAvailableMatrimonial(req.availableMatrimonial());
        p.setPriceSimple(req.priceSimple());
        p.setPriceMatrimonial(req.priceMatrimonial());
        p.setAdmissionType(req.admissionType());
        p.setResidentProfile(req.residentProfile());
        p.setBathroomsCount(req.bathroomsCount());
        p.setBathroomType(req.bathroomType());
        p.setHasParking(Boolean.TRUE.equals(req.hasParking()));
        p.setAmenities(req.amenities() == null ? new HashSet<>() : new HashSet<>(req.amenities()));
        p.setNearbyTags(req.nearbyTags() == null ? new HashSet<>() : new HashSet<>(req.nearbyTags()));
        service.applyStudyCenters(p, req.studyCenters());
        service.applyPublicContact(p, req, me);
        p.setStatus(PensionStatus.DRAFT);
        service.applyDraftStep(p, req.draftStep());
        p = service.createWithNewOrg(me, p); // crea org + OWNER
        return dto(p, principal);
    }

    @GetMapping("/{id}")
    @PreAuthorize("@authz.canViewPension(#principal, #id)")
    public PensionDTO get(@AuthenticationPrincipal OAuth2User principal, @PathVariable Long id) {
        Pension p = repo.findById(id).orElseThrow();
        return dto(p, principal);
    }

    @PutMapping("/{id}")
    @PreAuthorize("@authz.canEditPensionFields(#principal, #id)")
    public PensionDTO update(@AuthenticationPrincipal OAuth2User principal,
                             @PathVariable Long id,
                             @RequestBody PensionDTO req) {
        Pension p = repo.findById(id).orElseThrow();
        requireExpectedVersion(p, req.version());
        try {
            p = service.update(p, req); // actualiza, valida y persiste atómicamente
            return dto(p, principal);
        } catch (OptimisticLockingFailureException ex) {
            throw editConflict();
        }
    }

    @PatchMapping("/{id}/draft-step")
    @PreAuthorize("@authz.canEditPensionFields(#principal, #id)")
    public void updateDraftStep(@AuthenticationPrincipal OAuth2User principal,
                                @PathVariable Long id,
                                @RequestBody DraftStepRequest request) {
        if (request == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "Debes indicar el paso del borrador");
        }
        service.updateDraftStep(id, request.step());
    }

    @GetMapping("/{id}/publication-validation")
    @PreAuthorize("@authz.canEditPensionFields(#principal, #id)")
    public PensionPublicationReportDTO publicationValidation(@AuthenticationPrincipal OAuth2User principal,
                                                               @PathVariable Long id) {
        Pension p = repo.findById(id).orElseThrow();
        return publication.report(p);
    }

    @GetMapping("/{id}/preview")
    @PreAuthorize("@authz.canViewPension(#principal, #id)")
    public PensionPreviewDTO preview(@AuthenticationPrincipal OAuth2User principal,
                                     @PathVariable Long id) {
        Pension p = repo.findById(id).orElseThrow();
        return new PensionPreviewDTO(
                p.getStatus(),
                publication.report(p),
                details.toPublicDetail(p)
        );
    }

    @PatchMapping("/{id}/publication")
    @PreAuthorize("@authz.canEditPensionFields(#principal, #id)")
    public PensionDTO changePublication(@AuthenticationPrincipal OAuth2User principal,
                                        @PathVariable Long id,
                                        @RequestBody PublicationRequest request) {
        if (request == null || request.status() == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Debes indicar el estado de publicación"
            );
        }

        Pension p = repo.findById(id).orElseThrow();
        requireExpectedVersion(p, request.version());
        try {
            return dto(service.changePublication(p, request.status()), principal);
        } catch (OptimisticLockingFailureException ex) {
            throw editConflict();
        }
    }

    @PatchMapping("/{id}/availability")
    @PreAuthorize("@authz.canUpdateAvailability(#principal, #id)")
    public PensionDTO updateAvailability(@AuthenticationPrincipal OAuth2User principal,
                                         @PathVariable Long id,
                                         @RequestBody AvailabilityDTO body) {
        Pension p = repo.findById(id).orElseThrow();
        if (body.availableSimple() != null) {
            int capacity = Math.max(0, p.getCapacitySimple() == null ? 0 : p.getCapacitySimple());
            p.setAvailableSimple(Math.min(capacity, Math.max(0, body.availableSimple())));
        }
        if (body.availableMatrimonial() != null) {
            int capacity = Math.max(0, p.getCapacityMatrimonial() == null ? 0 : p.getCapacityMatrimonial());
            p.setAvailableMatrimonial(Math.min(capacity, Math.max(0, body.availableMatrimonial())));
        }
        p.setAvailabilityUpdatedAt(OffsetDateTime.now());
        return dto(repo.save(p), principal); // clamp en @PreUpdate asegura <= capacidad
    }

    @PatchMapping("/{id}/availability/confirm")
    @PreAuthorize("@authz.canUpdateAvailability(#principal, #id)")
    public PensionDTO confirmAvailability(@AuthenticationPrincipal OAuth2User principal,
                                          @PathVariable Long id) {
        Pension p = repo.findById(id).orElseThrow();
        p.setAvailabilityUpdatedAt(OffsetDateTime.now());
        return dto(repo.save(p), principal);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@authz.canRemovePension(#principal, #id)")
    public void delete(@AuthenticationPrincipal OAuth2User principal,
                       @PathVariable Long id) {
        repo.deleteById(id);
    }

    @PatchMapping("/{id}/owner")
    @PreAuthorize("@authz.isOwner(#principal, #id)")
    public PensionDTO changeOwner(@AuthenticationPrincipal OAuth2User principal,@PathVariable Long id,
                                  @RequestBody ChangeOwnerRequest req) {
        Pension p = repo.findById(id).orElseThrow();

        User newOwner = null;
        if (req.userId() != null) {
            newOwner = users.findById(req.userId()).orElseThrow();
        } else if (req.email() != null && !req.email().isBlank()) {
            newOwner = users.findByEmail(req.email().trim().toLowerCase())
                    .orElseThrow(() -> new IllegalArgumentException("Usuario no encontrado"));
        } else {
            throw new IllegalArgumentException("Debe indicar userId o email");
        }

        // (Opcional) validar que pertenezca a la misma organización de la pensión:
        // requireSameOrg(p.getOrg(), newOwner);

        return dto(service.transferOwner(p, newOwner), principal);
    }



    private void requireExpectedVersion(Pension pension, Long expectedVersion) {
        if (expectedVersion == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.PRECONDITION_REQUIRED,
                    "Falta la versión de edición. Recarga la publicación antes de guardar.");
        }
        if (!Objects.equals(pension.getVersion(), expectedVersion)) {
            throw editConflict();
        }
    }

    private org.springframework.web.server.ResponseStatusException editConflict() {
        return new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.CONFLICT,
                "La publicación cambió en otra pestaña o dispositivo. Tus cambios locales no fueron sobrescritos.");
    }

}
