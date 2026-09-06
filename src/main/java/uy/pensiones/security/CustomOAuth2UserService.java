package uy.pensiones.security;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uy.pensiones.service.EmailVerificationService;
import uy.pensiones.model.User;
import uy.pensiones.repo.UserRepository;
import uy.pensiones.enums.UserRole;

import java.util.Map;
import java.util.Set;

@Service
public class CustomOAuth2UserService extends DefaultOAuth2UserService {

  private final UserRepository users;
  private final EmailVerificationService emailVerification;

  public CustomOAuth2UserService(UserRepository users,
                                 EmailVerificationService emailVerification) {
    this.users = users;
    this.emailVerification = emailVerification;
  }

  @Override
  @Transactional
  public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
    OAuth2User delegate = super.loadUser(userRequest);

    String registrationId = userRequest.getClientRegistration().getRegistrationId().toUpperCase();
    Map<String, Object> attrs = delegate.getAttributes();

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

    Map<String, Object> newAttrs = new java.util.HashMap<>(attrs);
    newAttrs.put("appUser", user);

    var authorities = Set.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
    return new DefaultOAuth2User(authorities, newAttrs, "sub");
  }
}
