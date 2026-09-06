package uy.pensiones.web;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uy.pensiones.model.User;
import uy.pensiones.repo.UserRepository;
import uy.pensiones.service.EmailVerificationService;

@RestController
@RequestMapping("/api/auth")
public class AuthControllerPatch {

  private final EmailVerificationService emailVerification;
  private final UserRepository users;

  public AuthControllerPatch(EmailVerificationService emailVerification, UserRepository users) {
    this.emailVerification = emailVerification;
    this.users = users;
  }

  @PostMapping("/resend-verification")
  public void resend(@AuthenticationPrincipal OAuth2User principal) {
    if (principal == null) return;
    User u = (User) principal.getAttribute("appUser");
    if (u == null) {
      String email = principal.getAttribute("email");
      u = users.findByEmail(email).orElse(null);
    }
    if (u != null && !u.isEmailVerified()) {
      emailVerification.issueIfNeeded(u);
    }
  }
}
