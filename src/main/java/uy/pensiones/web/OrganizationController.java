package uy.pensiones.web;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;
import uy.pensiones.model.Membership;
import uy.pensiones.service.OrgService;
import uy.pensiones.model.Organization;
import uy.pensiones.model.User;
import uy.pensiones.repo.UserRepository;
import uy.pensiones.web.dto.OrgDtos;

import java.util.List;

@RestController
@RequestMapping("/api/orgs")
public class OrganizationController {

    private final OrgService orgs;
    private final UserRepository users;

    public OrganizationController(OrgService orgs, UserRepository users) {
        this.orgs = orgs;
        this.users = users;
    }

    private User current(@AuthenticationPrincipal OAuth2User p) {
        if (p == null) return null;
        User u = (User) p.getAttribute("appUser");
        if (u != null) return u;
        String email = p.getAttribute("email");
        return users.findByEmail(email).orElse(null);
    }

    @PostMapping
    public OrgDtos.OrgSummary create(@AuthenticationPrincipal OAuth2User principal,
                                     @RequestBody OrgDtos.CreateOrgRequest req) {
        User me = current(principal);
        Organization org = orgs.createOrg(me, req.name());
        return new OrgDtos.OrgSummary(org.getId(), org.getName(), org.getOwner().getEmail());
    }

    @GetMapping("/mine")
    public List<Membership> mine(@AuthenticationPrincipal OAuth2User principal) {
        User me = current(principal);
        return orgs.myMemberships(me.getId());
    }

    @GetMapping("/{orgId}/members")
    @PreAuthorize("@authz.canManageMembers(#principal.getAttribute('appUser') != null ? #principal.getAttribute('appUser').id : @userRepository.findByEmail(#principal.getAttribute('email')).get().id, #orgId)")
    public List<OrgDtos.MemberDto> members(@AuthenticationPrincipal OAuth2User principal,
                                           @PathVariable Long orgId) {
        return orgs.members(orgId).stream()
                .map(m -> new OrgDtos.MemberDto(
                        m.getId(),
                        m.getUser().getId(),
                        m.getUser().getName(),
                        m.getUser().getEmail(),
                        m.getRole(),
                        m.getStatus().name()
                )).toList();
    }

    @PostMapping("/{orgId}/members/{membershipId}/role")
    @PreAuthorize("@authz.canManageMembers(#principal.getAttribute('appUser') != null ? #principal.getAttribute('appUser').id : @userRepository.findByEmail(#principal.getAttribute('email')).get().id, #orgId)")
    public void changeRole(@AuthenticationPrincipal OAuth2User principal,
                           @PathVariable Long orgId,
                           @PathVariable Long membershipId,
                           @RequestBody OrgDtos.ChangeRoleRequest req) {
        orgs.changeRole(orgId, membershipId, req.role());
    }

    @DeleteMapping("/{orgId}/members/{membershipId}")
    @PreAuthorize("@authz.canManageMembers(#principal.getAttribute('appUser') != null ? #principal.getAttribute('appUser').id : @userRepository.findByEmail(#principal.getAttribute('email')).get().id, #orgId)")
    public void remove(@AuthenticationPrincipal OAuth2User principal,
                       @PathVariable Long orgId,
                       @PathVariable Long membershipId) {
        orgs.removeMember(orgId, membershipId);
    }
}
