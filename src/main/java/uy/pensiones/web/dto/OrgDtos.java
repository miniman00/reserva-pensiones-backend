package uy.pensiones.web.dto;

import uy.pensiones.model.Membership;

public class OrgDtos {
    public record CreateOrgRequest(String name) {}
    public record OrgSummary(Long id, String name, String ownerEmail) {}
    public record InviteRequest(String email, Membership.Role role) {}
    public record AcceptInviteRequest(String token) {}
    public record MemberDto(Long membershipId, Long userId, String name, String email, Membership.Role role, String status) {}
    public record ChangeRoleRequest(Membership.Role role) {}
}
