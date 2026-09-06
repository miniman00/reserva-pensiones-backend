package uy.pensiones.web;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import uy.pensiones.model.User;
import uy.pensiones.repo.UserRepository;
import uy.pensiones.service.FounderFeaturedBenefitService;
import uy.pensiones.service.OwnerCommercialService;
import uy.pensiones.service.SubscriptionFeaturedBenefitService;

@RestController
@RequestMapping("/api/owner/commercial")
public class OwnerCommercialController {

    private final OwnerCommercialService commercial;
    private final UserRepository users;
    private final SubscriptionFeaturedBenefitService featuredBenefits;
    private final FounderFeaturedBenefitService founderBenefits;

    public OwnerCommercialController(OwnerCommercialService commercial,
                                     UserRepository users,
                                     SubscriptionFeaturedBenefitService featuredBenefits,
                                     FounderFeaturedBenefitService founderBenefits) {
        this.commercial = commercial;
        this.users = users;
        this.featuredBenefits = featuredBenefits;
        this.founderBenefits = founderBenefits;
    }

    @GetMapping("/overview")
    public OwnerCommercialService.CommercialOverview overview(@AuthenticationPrincipal OAuth2User principal) {
        return commercial.overview(resolveUserId(principal));
    }

    @PostMapping("/subscription-featured")
    public SubscriptionFeaturedBenefitService.ActivationResult activateSubscriptionFeatured(
            @AuthenticationPrincipal OAuth2User principal,
            @RequestBody SubscriptionFeaturedRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La solicitud no es válida");
        }
        return featuredBenefits.activate(resolveUserId(principal), request.pensionId(), request.days());
    }

    @PostMapping("/founder-featured")
    public FounderFeaturedBenefitService.ActivationResult activateFounderFeatured(
            @AuthenticationPrincipal OAuth2User principal,
            @RequestBody FounderFeaturedRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La solicitud no es válida");
        }
        return founderBenefits.activate(resolveUserId(principal), request.pensionId());
    }

    @PostMapping("/founder-featured/{promotionId}/deactivate")
    public FounderFeaturedBenefitService.DeactivationResult deactivateFounderFeatured(
            @AuthenticationPrincipal OAuth2User principal,
            @PathVariable Long promotionId) {
        return founderBenefits.deactivate(resolveUserId(principal), promotionId);
    }

    public record SubscriptionFeaturedRequest(Long pensionId, int days) {}
    public record FounderFeaturedRequest(Long pensionId) {}

    private Long resolveUserId(OAuth2User principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Debes iniciar sesión para continuar");
        }
        Object appUser = principal.getAttribute("appUser");
        if (appUser instanceof User user && user.getId() != null) return user.getId();
        String email = principal.getAttribute("email");
        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No se pudo identificar al usuario autenticado");
        }
        return users.findByEmail(email)
                .map(User::getId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "No se pudo identificar al usuario autenticado"));
    }
}
