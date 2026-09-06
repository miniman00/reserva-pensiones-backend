package uy.pensiones.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;
import uy.pensiones.repo.PensionInviteRepository;
import uy.pensiones.service.InviteService;
import uy.pensiones.model.User;
import uy.pensiones.repo.UserRepository;
import uy.pensiones.web.dto.InviteDTO;
import uy.pensiones.web.dto.OrgDtos;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class InviteController {

    private final InviteService invites;
    private final UserRepository users;
    private final PensionInviteRepository inviteRepo;

    public InviteController(InviteService invites, UserRepository users,
                             PensionInviteRepository inviteRepo) {
        this.invites = invites;
        this.users = users;
        this.inviteRepo = inviteRepo;
    }
    public record AcceptInviteRequest(String token) {}

    private User current(@AuthenticationPrincipal OAuth2User p) {
        if (p == null) return null;
        User u = (User) p.getAttribute("appUser");
        if (u != null) return u;
        String email = p.getAttribute("email");
        return users.findByEmail(email).orElse(null);
    }

    /**
     * Crear invitación a una PENSIÓN.
     * OJO: cambiamos la ruta para que reciba pensionId (no orgId),
     * porque el servicio createInvite trabaja con pensión.
     */
    @PostMapping("/pensions/{pensionId}/invites")
    @PreAuthorize("@authz.canManageMembers(#principal, #pensionId)")
    public InviteDTO createInvite(@AuthenticationPrincipal OAuth2User principal,
                                  @PathVariable Long pensionId,
                                  @RequestBody InviteDTO req) {
        // obtener el usuario actual por email del principal
        String email = (String) principal.getAttribute("email");
        User inviter = users.findByEmail(email.toLowerCase()).orElseThrow();

        // service espera: (pensionId, emailInvitado, rol, inviterUserId)
        var inv = invites.createInvite(pensionId, req.email(), req.role(), inviter.getId());
        return InviteDTO.of(inv);
    }

}
