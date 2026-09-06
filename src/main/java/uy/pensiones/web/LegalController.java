package uy.pensiones.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import uy.pensiones.config.AppProperties;
import uy.pensiones.model.User;
import uy.pensiones.repo.UserRepository;
import uy.pensiones.service.LegalDocuments;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@RestController
public class LegalController {

    public record LegalInfoDTO(
            String termsVersion,
            String privacyVersion,
            String effectiveDate,
            String operatorName,
            String contactEmail
    ) {}

    public record AcceptLegalRequest(
            @NotNull(message = "Debes aceptar los Términos de Uso")
            @AssertTrue(message = "Debes aceptar los Términos de Uso")
            Boolean termsAccepted,

            @NotNull(message = "Debes aceptar la Política de Privacidad")
            @AssertTrue(message = "Debes aceptar la Política de Privacidad")
            Boolean privacyAccepted,

            @NotBlank @Size(max = 20)
            String termsVersion,

            @NotBlank @Size(max = 20)
            String privacyVersion
    ) {}

    private final UserRepository users;
    private final AppProperties properties;

    public LegalController(UserRepository users, AppProperties properties) {
        this.users = users;
        this.properties = properties;
    }

    @GetMapping("/api/public/legal/current")
    public LegalInfoDTO current() {
        return new LegalInfoDTO(
                LegalDocuments.TERMS_VERSION,
                LegalDocuments.PRIVACY_VERSION,
                LegalDocuments.EFFECTIVE_DATE.toString(),
                properties.getLegal().getOperatorName(),
                properties.getLegal().getContactEmail()
        );
    }

    @PostMapping("/api/legal/accept")
    public LegalInfoDTO accept(@AuthenticationPrincipal OAuth2User principal,
                               @Valid @RequestBody AcceptLegalRequest request) {
        User user = resolveUser(principal);
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Debes iniciar sesión");
        }

        if (!LegalDocuments.TERMS_VERSION.equals(request.termsVersion())
                || !LegalDocuments.PRIVACY_VERSION.equals(request.privacyVersion())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Los documentos legales fueron actualizados. Revísalos y vuelve a aceptar."
            );
        }

        user.setTermsAcceptedVersion(LegalDocuments.TERMS_VERSION);
        user.setPrivacyAcceptedVersion(LegalDocuments.PRIVACY_VERSION);
        user.setLegalAcceptedAt(OffsetDateTime.now(ZoneOffset.UTC));
        users.save(user);
        return current();
    }

    private User resolveUser(OAuth2User principal) {
        if (principal == null) return null;
        User appUser = principal.getAttribute("appUser");
        if (appUser != null) return users.findById(appUser.getId()).orElse(null);
        String email = principal.getAttribute("email");
        return email == null ? null : users.findByEmail(email).orElse(null);
    }
}
