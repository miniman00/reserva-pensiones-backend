package uy.pensiones.web;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;
import uy.pensiones.model.*;
import uy.pensiones.repo.*;
import uy.pensiones.service.InviteService;

import java.util.Map;

@RestController
@RequestMapping("/api/invites")
public class InvitePublicController {

    private final PensionInviteRepository invites;
    private final InviteService inviteSvc;
    private final UserRepository users;

    public InvitePublicController(PensionInviteRepository invites, InviteService inviteSvc, UserRepository users) {
        this.invites = invites; this.inviteSvc = inviteSvc; this.users = users;
    }

    // público: info básica
    @GetMapping("/{token}")
    public Map<String,Object> get(@PathVariable String token) {
        var inv = invites.findByToken(token).orElseThrow();
        return Map.of(
                "email", inv.getEmail(),
                "pensionName", inv.getPension().getName(),
                "status", inv.getStatus().name(),
                "expiresAt", inv.getExpiresAt(),
                "role", inv.getRole().name()
        );
    }

    // requiere login
    @PostMapping("/{token}/accept")
    public Map<String,Object> accept(@PathVariable String token, Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof OAuth2User o))
            throw new RuntimeException("Necesitás iniciar sesión.");

        String email = (String) o.getAttribute("email");
        var u = users.findByEmail(email.toLowerCase()).orElseThrow();
        return inviteSvc.accept(token, u.getId(), u.getEmail());
    }
}
