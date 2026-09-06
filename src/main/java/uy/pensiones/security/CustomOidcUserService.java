package uy.pensiones.security;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uy.pensiones.model.User;
import uy.pensiones.repo.UserRepository;
import uy.pensiones.service.EmailVerificationService;
import uy.pensiones.enums.UserRole;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@Service
public class CustomOidcUserService extends OidcUserService {

  private final UserRepository users;
  private final EmailVerificationService emailVerification;

  public CustomOidcUserService(UserRepository users, EmailVerificationService emailVerification) {
    this.users = users;
    this.emailVerification = emailVerification;
  }

  @Override
  @Transactional
  public OidcUser loadUser(OidcUserRequest userRequest) {
    OidcUser oidc = super.loadUser(userRequest);

    String registrationId = userRequest.getClientRegistration().getRegistrationId().toUpperCase(); // GOOGLE
    Map<String, Object> attrs = new HashMap<>(oidc.getAttributes());

    String sub = String.valueOf(attrs.getOrDefault("sub", ""));
    String email = (String) attrs.getOrDefault("email", "");
    String name = (String) attrs.getOrDefault("name", "");
    boolean emailVerified = Boolean.TRUE.equals(attrs.get("email_verified"));

    User user = users.findByProviderAndProviderId(registrationId, sub)
        .orElseGet(() -> users.findByEmail(email).orElse(null));

    if (user == null) {
      user = User.builder()
          .provider(registrationId)
          .providerId(sub)
          .email(email)
          .name(name)
          .role(UserRole.SEEKER)
          .build();
    } else {
      user.setProvider(registrationId);
      user.setProviderId(sub);
      if (name != null) user.setName(name);
      if (email != null) user.setEmail(email);
    }
    try {
      if (emailVerified) user.setEmailVerified(true);
    } catch (Throwable ignored) {}

    user = users.save(user);
    try {
      if (!user.isEmailVerified()) emailVerification.issueIfNeeded(user);
    } catch (Throwable ignored) {}

    attrs.put("appUser", user);
    var authorities = Set.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
    // nameAttributeKey -> "sub" para Google OIDC
    return new DefaultOidcUser(authorities, oidc.getIdToken(), oidc.getUserInfo(), "sub");
  }
}
