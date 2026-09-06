// uy/pensiones/web/MeController.java
package uy.pensiones.web;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;
import uy.pensiones.model.User;
import uy.pensiones.repo.UserRepository;
import uy.pensiones.service.LegalDocuments;

import java.time.OffsetDateTime;

@RestController
@RequestMapping("/api")
public class MeController {

  record CurrentUserDto(Long id, String name, String email, String role,
                        boolean emailVerified, String provider,
                        String phone, String countryCode,
                        boolean legalAcceptanceRequired,
                        String termsAcceptedVersion,
                        String privacyAcceptedVersion,
                        OffsetDateTime legalAcceptedAt,
                        String currentTermsVersion,
                        String currentPrivacyVersion,
                        boolean suspended,
                        OffsetDateTime suspendedAt,
                        String suspensionReason) {}

  record UpdateMeRequest(String name, String phone, String countryCode) {}

  private final UserRepository users;

  public MeController(UserRepository users) { this.users = users; }

  @GetMapping("/me")
  public CurrentUserDto me(@AuthenticationPrincipal OAuth2User principal) {
    User u = resolveUser(principal);
    if (u == null) return null;
    return toDto(u);
  }

  @PutMapping("/users/me")
  public CurrentUserDto update(@AuthenticationPrincipal OAuth2User principal,
                               @RequestBody UpdateMeRequest req) {
    User u = resolveUser(principal);
    if (u == null) return null;
    if (req.name() != null) u.setName(req.name().trim());
    if (req.phone() != null) u.setPhone(req.phone().trim());
    if (req.countryCode() != null) u.setCountryCode(req.countryCode().trim().toUpperCase());
    u = users.save(u);
    return toDto(u);
  }

  private User resolveUser(OAuth2User p) {
    if (p == null) return null;
    User u = (User) p.getAttribute("appUser");
    if (u != null) return users.findById(u.getId()).orElse(null);
    String email = p.getAttribute("email");
    return email == null ? null : users.findByEmail(email).orElse(null);
  }

  private CurrentUserDto toDto(User u) {
    // role: si tu User tiene un enum Role, ajústalo aquí
    String role = "SEEKER";
    try { role = u.getClass().getMethod("getRole").invoke(u).toString(); } catch (Exception ignored) {}
    return new CurrentUserDto(u.getId(), u.getName(), u.getEmail(), role,
            u.isEmailVerified(), u.getProvider(), u.getPhone(), u.getCountryCode(),
            !LegalDocuments.isCurrent(u),
            u.getTermsAcceptedVersion(), u.getPrivacyAcceptedVersion(), u.getLegalAcceptedAt(),
            LegalDocuments.TERMS_VERSION, LegalDocuments.PRIVACY_VERSION,
            u.isSuspended(), u.getSuspendedAt(), u.getSuspensionReason());
  }
}
