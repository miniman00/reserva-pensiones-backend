package uy.pensiones.web;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;
import uy.pensiones.enums.InviteStatus;
import uy.pensiones.enums.NotificationType;
import uy.pensiones.enums.PensionRole;
import uy.pensiones.model.*;
import uy.pensiones.repo.*;
import uy.pensiones.service.InviteService;
import uy.pensiones.service.NotificationService;
import uy.pensiones.web.dto.*;

import java.util.*;

@RestController
@RequestMapping("/api/pensions/{pensionId}")
public class PensionMembersController {

    private final PensionMemberRepository members;
    private final PensionInviteRepository invites;
    private final InviteService inviteSvc;
    private final UserRepository users;
    private final NotificationService notifications;

    public PensionMembersController(PensionMemberRepository members, PensionInviteRepository invites,
                                    InviteService inviteSvc, UserRepository users,
                                    NotificationService notifications) {
        this.members = members; this.invites = invites; this.inviteSvc = inviteSvc; this.users = users;
        this.notifications = notifications;
    }

    @GetMapping("/members")
    @PreAuthorize("@authz.canManageMembers(#principal, #pensionId)")
    public Map<String,Object> list(@AuthenticationPrincipal OAuth2User principal,@PathVariable Long pensionId) {
        List<MemberDTO> ms = members.findByPensionId(pensionId).stream().map(MemberDTO::of).toList();
        List<InviteDTO> is = invites.findByPensionIdAndStatus(pensionId, InviteStatus.PENDING).stream()
                .map(InviteDTO::of).toList();
        return Map.of("members", ms, "invites", is);
    }

    public record InviteRequest(String email, PensionRole role) {}


    @DeleteMapping("/invites/{inviteId}")
    @PreAuthorize("@authz.canManageMembers(#principal, #pensionId)")
    public void revoke(@AuthenticationPrincipal OAuth2User principal,@PathVariable Long pensionId, @PathVariable Long inviteId) {
        inviteSvc.revokeInvite(pensionId, inviteId);
    }

    @DeleteMapping("/members/{memberId}")
    @PreAuthorize("@authz.canManageMembers(#principal, #pensionId)")
    public void removeMember(@AuthenticationPrincipal OAuth2User principal,@PathVariable Long pensionId, @PathVariable Long memberId) {
        var member = members.findByIdAndPensionId(memberId, pensionId).orElseThrow();
        String pensionName = member.getPension().getName();
        notifications.create(
                member.getUser(),
                NotificationType.MEMBER_REMOVED,
                "Acceso removido",
                "Tu acceso como colaborador a " + pensionName + " fue removido.",
                "/profile/pensions"
        );
        members.delete(member);
    }
}
