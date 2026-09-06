package uy.pensiones.service;

import jakarta.transaction.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import uy.pensiones.model.Membership;
import uy.pensiones.model.Organization;
import uy.pensiones.model.Pension;
import uy.pensiones.model.User;
import uy.pensiones.enums.PensionStatus;
import uy.pensiones.repo.*;
import uy.pensiones.web.PensionController;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.time.OffsetDateTime;

@Service
public class PensionService {
    private final PensionRepository pensions;
    private final OrganizationRepository orgs;
    private final OrgService orgService;
    private final MembershipRepository memberships;
    private final PensionMemberRepository pensionMembers;
    private final StudyCenterCatalogService studyCenterCatalog;
    private final OwnerEntitlementService entitlements;
    private final PensionPublicationService publication;
    private final FounderLaunchCampaignService founderCampaign;
    private final FounderFeaturedBenefitService founderFeaturedBenefits;

    public PensionService(PensionRepository pensions, OrganizationRepository orgs,
                          OrgService orgService, MembershipRepository memberships,
                          PensionMemberRepository pensionMembers, StudyCenterCatalogService studyCenterCatalog,
                          OwnerEntitlementService entitlements, PensionPublicationService publication,
                          FounderLaunchCampaignService founderCampaign,
                          FounderFeaturedBenefitService founderFeaturedBenefits) {
        this.pensions = pensions; this.orgs = orgs;
        this.orgService = orgService; this.memberships = memberships;
        this.pensionMembers = pensionMembers;
        this.studyCenterCatalog = studyCenterCatalog;
        this.entitlements = entitlements;
        this.publication = publication;
        this.founderCampaign = founderCampaign;
        this.founderFeaturedBenefits = founderFeaturedBenefits;
    }

    public List<Pension> myPensions(Long userId) {
        var my = memberships.findByUserIdAndStatus(userId, Membership.MembershipStatus.ACTIVE);
        List<Long> orgIds = my.stream().map(m -> m.getOrg().getId()).distinct().toList();
        List<Long> pensionIds = pensionMembers.findPensionIdsByUserId(userId);

        Map<Long, Pension> result = new LinkedHashMap<>();

        // Modelo actual: pensiones cuyo owner_id es el usuario.
        pensions.findByOwnerId(userId).forEach(p -> result.put(p.getId(), p));

        // Compatibilidad con pensiones antiguas, previas a owner_id.
        if (!orgIds.isEmpty()) {
            pensions.findByOrgIdIn(orgIds).stream()
                    .filter(p -> p.getOwner() == null)
                    .forEach(p -> result.put(p.getId(), p));
        }

        // Colaboraciones actuales (AVAIL_ONLY / MANAGER).
        if (!pensionIds.isEmpty()) {
            pensions.findAllById(pensionIds).forEach(p -> result.put(p.getId(), p));
        }
        return List.copyOf(result.values());
    }

    @Transactional
    public Pension createWithNewOrg(User creator, Pension p) {
        entitlements.requireCanCreatePension(creator.getId());
        // crea una organización homónima para aislar permisos por pensión
        Organization org = orgService.createOrg(creator, p.getName());
        p.setOrg(org);
        p.setCreatedBy(creator);
        p.setOwner(creator);
        studyCenterCatalog.ensureCatalogEntries(p, p.getStudyCenters());
        return pensions.save(p);
    }

    @Transactional
    public Pension update(Pension target, PensionController.PensionDTO d) {
        target.setName(d.name());
        target.setDescription(d.description());
        target.setCountryCode(d.countryCode());
        target.setAddressLine1(d.addressLine1());
        target.setCity(d.city());
        target.setState(d.state());
        target.setPostalCode(d.postalCode());
        target.setNeighborhood(d.neighborhood());
        target.setLat(d.lat());
        target.setLng(d.lng());

        int oldAvailableSimple = safe(target.getAvailableSimple());
        int oldAvailableMatrimonial = safe(target.getAvailableMatrimonial());

        target.setCapacitySimple(d.capacitySimple());
        target.setCapacityMatrimonial(d.capacityMatrimonial());
        target.setAvailableSimple(d.availableSimple());
        target.setAvailableMatrimonial(d.availableMatrimonial());

        if (oldAvailableSimple != safe(d.availableSimple())
                || oldAvailableMatrimonial != safe(d.availableMatrimonial())) {
            target.setAvailabilityUpdatedAt(OffsetDateTime.now());
        }

        target.setPriceSimple(d.priceSimple());
        target.setPriceMatrimonial(d.priceMatrimonial());

        target.setAdmissionType(d.admissionType());
        target.setResidentProfile(d.residentProfile());
        target.setBathroomsCount(d.bathroomsCount());
        target.setBathroomType(d.bathroomType());
        target.setHasParking(Boolean.TRUE.equals(d.hasParking()));
        applyPublicContact(target, d, null);
        target.setAmenities(d.amenities() == null ? new HashSet<>() : new HashSet<>(d.amenities()));
        target.setNearbyTags(d.nearbyTags() == null ? new HashSet<>() : new HashSet<>(d.nearbyTags()));
        applyStudyCenters(target, d.studyCenters());
        applyDraftStep(target, d.draftStep());
        studyCenterCatalog.ensureCatalogEntries(target, target.getStudyCenters());
        publication.requirePublishedEditStillValid(target);
        return pensions.saveAndFlush(target);
    }

    @Transactional
    public Pension changePublication(Pension target, PensionStatus nextStatus) {
        if (target == null || nextStatus == null) {
            throw badRequest("La pensión y el estado de publicación son obligatorios.");
        }

        PensionStatus previousStatus = target.getStatus();
        if (nextStatus == PensionStatus.PUBLISHED) {
            publication.requirePublishable(target);
        }

        target.setStatus(nextStatus);
        if (nextStatus != PensionStatus.DRAFT) {
            target.setDraftStep(null);
        }

        Pension saved = pensions.saveAndFlush(target);
        if (previousStatus != PensionStatus.PUBLISHED && nextStatus == PensionStatus.PUBLISHED) {
            founderCampaign.onFirstValidPublication(saved);
        }
        return saved;
    }

    @Transactional
    public Pension transferOwner(Pension target, User newOwner) {
        if (target == null || newOwner == null) {
            throw badRequest("La pensión y el nuevo responsable son obligatorios.");
        }
        entitlements.requireCanTakeOwnership(newOwner.getId(), target.getId());
        User previousOwner = target.getOwner() != null ? target.getOwner() : target.getCreatedBy();
        if (previousOwner != null && previousOwner.getId() != null
                && !previousOwner.getId().equals(newOwner.getId())) {
            founderFeaturedBenefits.cancelForOwnershipTransfer(target, OffsetDateTime.now());
        }
        target.setOwner(newOwner);
        // Una transferencia nunca debe dejar expuestos los datos del propietario anterior.
        target.setContactName(newOwner.getName());
        target.setContactPhone(newOwner.getPhone());
        target.setContactWhatsapp(newOwner.getPhone());
        target.setShowPhone(false);
        target.setShowWhatsapp(false);
        return pensions.save(target);
    }

    public void applyStudyCenters(Pension target, Set<String> values) {
        if (values == null || values.isEmpty()) {
            target.setStudyCenters(new LinkedHashSet<>());
            return;
        }
        if (values.size() > 12) {
            throw badRequest("Puedes asociar hasta 12 centros de estudio por pensión.");
        }

        Map<String, String> normalized = new LinkedHashMap<>();
        for (String raw : values) {
            String value = normalizeStudyCenter(raw);
            if (value == null) continue;
            normalized.putIfAbsent(value.toLowerCase(Locale.ROOT), value);
        }
        target.setStudyCenters(new LinkedHashSet<>(normalized.values()));
    }

    private String normalizeStudyCenter(String raw) {
        String value = clean(raw);
        if (value == null) return null;
        value = value.replaceAll("\\s+", " ");
        if (value.length() > 140) {
            throw badRequest("El nombre de un centro de estudio supera los 140 caracteres.");
        }
        if (value.chars().anyMatch(Character::isISOControl)) {
            throw badRequest("El nombre de un centro de estudio contiene caracteres no válidos.");
        }
        return value;
    }

    public void applyPublicContact(Pension target, PensionController.PensionDTO d, User fallbackUser) {
        String contactName = clean(d.contactName());
        String phone = clean(d.contactPhone());
        String whatsapp = clean(d.contactWhatsapp());

        if (fallbackUser != null) {
            if (contactName == null) contactName = clean(fallbackUser.getName());
            if (phone == null) phone = clean(fallbackUser.getPhone());
            if (whatsapp == null) whatsapp = clean(fallbackUser.getPhone());
        }

        validateContactValue("Nombre de contacto", contactName, 120, false);
        validateContactValue("Teléfono", phone, 30, true);
        validateContactValue("WhatsApp", whatsapp, 30, true);

        boolean showPhone = Boolean.TRUE.equals(d.showPhone());
        boolean showWhatsapp = Boolean.TRUE.equals(d.showWhatsapp());
        if (showPhone && phone == null) {
            throw badRequest("Ingresa un teléfono antes de habilitar el botón Llamar.");
        }
        if (showWhatsapp && whatsapp == null) {
            throw badRequest("Ingresa un número de WhatsApp antes de habilitarlo públicamente.");
        }
        if (showWhatsapp) {
            String normalizedWhatsapp = PublicContactNumbers.normalizeWhatsapp(whatsapp);
            if (normalizedWhatsapp == null) {
                throw badRequest("El WhatsApp público debe incluir código de país y tener un número internacional válido, por ejemplo +598 99 123 456.");
            }
            whatsapp = normalizedWhatsapp;
        }

        target.setContactName(contactName);
        target.setContactPhone(phone);
        target.setContactWhatsapp(whatsapp);
        target.setShowPhone(showPhone);
        target.setShowWhatsapp(showWhatsapp);
    }

    private void validateContactValue(String label, String value, int maxLength, boolean phoneLike) {
        if (value == null) return;
        if (value.length() > maxLength) {
            throw badRequest(label + " supera el máximo de " + maxLength + " caracteres.");
        }
        if (value.chars().anyMatch(ch -> Character.isISOControl(ch))) {
            throw badRequest(label + " contiene caracteres no válidos.");
        }
        if (phoneLike && !value.matches("^[0-9+() .\\-]{6,30}$")) {
            throw badRequest(label + " tiene un formato no válido.");
        }
        if (phoneLike && value.chars().filter(Character::isDigit).count() < 6) {
            throw badRequest(label + " debe contener al menos 6 dígitos.");
        }
    }

    private String clean(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    @Transactional
    public void updateDraftStep(Long pensionId, String rawStep) {
        String step = normalizeDraftStep(rawStep);
        if (pensions.updateDraftStep(pensionId, step) == 0) {
            Pension current = pensions.findById(pensionId).orElseThrow();
            if (current.getStatus() == uy.pensiones.enums.PensionStatus.DRAFT) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "No se pudo guardar el progreso del borrador. Recarga el editor e inténtalo nuevamente.");
            }
        }
    }

    public void applyDraftStep(Pension target, String rawStep) {
        if (target.getStatus() != uy.pensiones.enums.PensionStatus.DRAFT) {
            target.setDraftStep(null);
            return;
        }
        target.setDraftStep(normalizeDraftStep(rawStep));
    }

    private String normalizeDraftStep(String rawStep) {
        String step = clean(rawStep);
        if (step == null) return "basic";
        return switch (step.toLowerCase(Locale.ROOT)) {
            case "basic", "location", "characteristics", "pricing", "media", "contact", "publish" -> step.toLowerCase(Locale.ROOT);
            default -> throw badRequest("Paso de borrador inválido.");
        };
    }

    private int safe(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }
}
