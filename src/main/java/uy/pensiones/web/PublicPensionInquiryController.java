package uy.pensiones.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import uy.pensiones.enums.InquiryRoomType;
import uy.pensiones.enums.InquiryStatus;
import uy.pensiones.enums.NotificationType;
import uy.pensiones.mail.MailService;
import uy.pensiones.model.Pension;
import uy.pensiones.model.PensionInquiry;
import uy.pensiones.model.PensionPromotion;
import uy.pensiones.model.User;
import uy.pensiones.repo.PensionInquiryRepository;
import uy.pensiones.repo.PensionRepository;
import uy.pensiones.repo.PensionPromotionRepository;
import uy.pensiones.repo.UserRepository;
import uy.pensiones.service.NotificationService;
import uy.pensiones.service.PensionInquiryProtectionService;
import uy.pensiones.service.PensionCatalogQualityService;
import uy.pensiones.web.dto.PensionInquiryCreateRequest;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/public/pensions/{pensionId}/inquiries")
@Slf4j
public class PublicPensionInquiryController {

    private final PensionRepository pensions;
    private final PensionInquiryRepository inquiries;
    private final PensionPromotionRepository promotions;
    private final UserRepository users;
    private final MailService mail;
    private final NotificationService notifications;
    private final PensionInquiryProtectionService protection;
    private final PensionCatalogQualityService catalogQuality;

    public PublicPensionInquiryController(PensionRepository pensions,
                                          PensionInquiryRepository inquiries,
                                          PensionPromotionRepository promotions,
                                          UserRepository users,
                                          MailService mail,
                                          NotificationService notifications,
                                          PensionInquiryProtectionService protection,
                                          PensionCatalogQualityService catalogQuality) {
        this.pensions = pensions;
        this.inquiries = inquiries;
        this.promotions = promotions;
        this.users = users;
        this.mail = mail;
        this.notifications = notifications;
        this.protection = protection;
        this.catalogQuality = catalogQuality;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@PathVariable Long pensionId,
                                      @AuthenticationPrincipal OAuth2User principal,
                                      HttpServletRequest servletRequest,
                                      @Valid @RequestBody PensionInquiryCreateRequest request) {
        Pension pension = pensions.findWithOwnerById(pensionId)
                .filter(catalogQuality::isPubliclyVisible)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pensión no encontrada"));

        User requester = currentOrNull(principal);
        protection.checkRateLimit(
                requester == null ? null : requester.getId(),
                request.visitorKey(),
                servletRequest,
                pensionId
        );

        // Honeypot. Se responde como éxito para no enseñar al bot qué regla activó,
        // pero no se persiste ni se notifica nada.
        if (hasText(request.website())) {
            return Map.of("message", "Consulta enviada correctamente");
        }

        String email = normalizeEmail(request.email());
        String phone = trimToNull(request.phone());
        PensionPromotion attributedPromotion = resolveAttribution(request.featuredPromotionId(), pensionId);

        if (protection.isRecentDuplicate(pensionId, email, phone)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Ya recibimos una consulta con estos datos hace pocos minutos. No es necesario enviarla nuevamente."
            );
        }

        PensionInquiry inquiry = PensionInquiry.builder()
                .pension(pension)
                .requester(requester)
                .contactName(request.name().trim())
                .contactEmail(email)
                .contactPhone(phone)
                .roomType(request.roomType() == null ? InquiryRoomType.ANY : request.roomType())
                .moveInDate(request.moveInDate())
                .message(trimToNull(request.message()))
                .status(InquiryStatus.NEW)
                .attributedPromotion(attributedPromotion)
                .attributedPromotionProductCode(attributedProductCode(attributedPromotion))
                .attributedPromotionProductName(attributedProductName(attributedPromotion))
                .attributedPromotionTargetType(attributedPromotion == null || attributedPromotion.getTargetType() == null
                        ? null : attributedPromotion.getTargetType().name())
                .termsAcceptedVersion(request.termsVersion())
                .privacyAcceptedVersion(request.privacyVersion())
                .consentAcceptedAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build();

        inquiry = inquiries.save(inquiry);

        User recipient = pension.getOwner() != null ? pension.getOwner() : pension.getCreatedBy();
        try {
            notifications.create(
                    recipient,
                    NotificationType.INQUIRY_RECEIVED,
                    "Nueva consulta en " + pension.getName(),
                    inquiry.getContactName() + " envió una consulta de disponibilidad.",
                    "/profile/inquiries"
            );
        } catch (RuntimeException ex) {
            // La consulta ya quedó guardada: una falla secundaria no debe inducir
            // al usuario a reenviarla y crear duplicados.
            log.warn("Consulta {} guardada, pero no se pudo crear la notificación: {}",
                    inquiry.getId(), ex.getMessage());
        }

        if (recipient != null && recipient.getEmail() != null && !recipient.getEmail().isBlank()) {
            try {
                mail.sendPensionInquiry(
                        recipient.getEmail(),
                        pension.getName(),
                        inquiry.getContactName(),
                        inquiry.getContactEmail(),
                        inquiry.getContactPhone(),
                        inquiry.getRoomType().name(),
                        inquiry.getMoveInDate() == null ? null : inquiry.getMoveInDate().toString(),
                        inquiry.getMessage()
                );
            } catch (RuntimeException ex) {
                // La consulta es la operación principal. Si el outbox no puede encolarse
                // después de guardarla, no devolvemos 5xx porque induciría un reenvío duplicado.
                log.warn("Consulta {} guardada, pero no se pudo encolar el email: {}",
                        inquiry.getId(), ex.getMessage());
            }
        }

        return Map.of(
                "id", inquiry.getId(),
                "message", "Consulta enviada correctamente"
        );
    }

    private PensionPromotion resolveAttribution(Long promotionId, Long pensionId) {
        if (promotionId == null || promotionId <= 0) return null;
        // La atribución nunca debe impedir una consulta real: si el destacado venció entre
        // el click y el envío, o el cliente manipuló el id, simplemente no se atribuye.
        return promotions.findEffectiveAttribution(
                        promotionId, pensionId, OffsetDateTime.now(ZoneOffset.UTC))
                .orElse(null);
    }

    private String attributedProductCode(PensionPromotion promotion) {
        if (promotion == null) return null;
        if (promotion.getSource() == uy.pensiones.enums.PensionPromotionSource.SUBSCRIPTION_BENEFIT) {
            return "PLAN_FEATURED";
        }
        if (promotion.getProductVersion() == null || promotion.getProductVersion().getProduct() == null) {
            return "LEGACY_FEATURED";
        }
        return promotion.getProductVersion().getProduct().getCode();
    }

    private String attributedProductName(PensionPromotion promotion) {
        if (promotion == null) return null;
        if (promotion.getSource() == uy.pensiones.enums.PensionPromotionSource.SUBSCRIPTION_BENEFIT) {
            return "Destacado incluido en el plan";
        }
        if (promotion.getProductVersion() == null || promotion.getProductVersion().getProduct() == null) {
            return "Destacado legado";
        }
        return promotion.getProductVersion().getProduct().getName();
    }

    private User currentOrNull(OAuth2User principal) {
        if (principal == null) return null;

        User appUser = principal.getAttribute("appUser");
        if (appUser != null) {
            return users.findById(appUser.getId()).orElse(null);
        }

        String email = principal.getAttribute("email");
        return email == null ? null : users.findByEmail(email).orElse(null);
    }

    private String normalizeEmail(String value) {
        String normalized = trimToNull(value);
        return normalized == null ? null : normalized.toLowerCase(Locale.ROOT);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
