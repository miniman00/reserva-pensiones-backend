package uy.pensiones.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import uy.pensiones.model.User;
import uy.pensiones.repo.UserRepository;
import uy.pensiones.service.OwnerPaymentCheckoutService;
import uy.pensiones.service.OwnerPaymentQueryService;

@RestController
@RequestMapping("/api/owner/payments")
public class OwnerPaymentController {

    private final OwnerPaymentCheckoutService checkouts;
    private final OwnerPaymentQueryService queries;
    private final UserRepository users;

    public OwnerPaymentController(OwnerPaymentCheckoutService checkouts, OwnerPaymentQueryService queries, UserRepository users) {
        this.checkouts = checkouts;
        this.queries = queries;
        this.users = users;
    }

    @GetMapping
    public Page<OwnerPaymentQueryService.OwnerPaymentDTO> list(
            @AuthenticationPrincipal OAuth2User principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return queries.list(resolveUserId(principal), page, size);
    }

    @GetMapping("/{paymentId}")
    public OwnerPaymentQueryService.OwnerPaymentDTO detail(
            @AuthenticationPrincipal OAuth2User principal,
            @PathVariable Long paymentId) {
        return queries.detail(resolveUserId(principal), paymentId);
    }

    @PostMapping("/checkouts/subscriptions")
    public OwnerPaymentCheckoutService.CheckoutResponse subscription(
            @AuthenticationPrincipal OAuth2User principal,
            @Valid @RequestBody SubscriptionCheckoutRequest request) {
        return checkouts.createSubscriptionCheckout(
                resolveUserId(principal), request.planVersionId(), request.subscriptionPeriodMonths(), request.idempotencyKey());
    }

    @PostMapping("/checkouts/promotions")
    public OwnerPaymentCheckoutService.CheckoutResponse promotion(
            @AuthenticationPrincipal OAuth2User principal,
            @Valid @RequestBody PromotionCheckoutRequest request) {
        return checkouts.createPromotionCheckout(
                resolveUserId(principal), request.pensionId(), request.promotionProductVersionId(),
                request.studyCenterId(), request.idempotencyKey());
    }

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

    public record SubscriptionCheckoutRequest(
            @NotNull Long planVersionId,
            @Min(1) @Max(12) Integer subscriptionPeriodMonths,
            @NotBlank @Size(max = 64) String idempotencyKey
    ) {}

    public record PromotionCheckoutRequest(
            @NotNull Long pensionId,
            @NotNull Long promotionProductVersionId,
            Long studyCenterId,
            @NotBlank @Size(max = 64) String idempotencyKey
    ) {}
}
