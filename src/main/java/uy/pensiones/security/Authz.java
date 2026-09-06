package uy.pensiones.security;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;
import uy.pensiones.enums.PensionRole;
import uy.pensiones.model.Membership;
import uy.pensiones.model.Pension;
import uy.pensiones.model.PensionMember;
import uy.pensiones.model.User;
import uy.pensiones.repo.MembershipRepository;
import uy.pensiones.repo.PensionMemberRepository;
import uy.pensiones.repo.PensionRepository;
import uy.pensiones.repo.UserRepository;

import java.util.EnumSet;
import java.util.Set;

@Component("authz")
public class Authz {
    private final MembershipRepository memberships;
    private final PensionRepository pensions;
    private final UserRepository users;
    private final PensionMemberRepository members;
    public Authz(MembershipRepository memberships, PensionRepository pensions, UserRepository users, PensionMemberRepository members) {
        this.memberships = memberships;
        this.pensions = pensions;
        this.users = users;
        this.members = members;
    }

    private Long uidFrom(OAuth2User principal) {
        if (principal == null) return null;
        Object attr = principal.getAttribute("appUser");
        if (attr instanceof User u) return u.getId();
        String email = principal.getAttribute("email");
        return email == null ? null : users.findByEmail(email).map(User::getId).orElse(null);
    }

    public boolean hasAnyRoleInOrg(Long userId, Long orgId, Set<Membership.Role> roles) {
        return memberships.findByOrgIdAndUserId(orgId, userId)
                .map(m -> m.getStatus() == Membership.MembershipStatus.ACTIVE && roles.contains(m.getRole()))
                .orElse(false);
    }

    public boolean canEditPensionsInOrg(Long userId, Long orgId) {
        return memberships.findByOrgIdAndUserId(orgId, userId)
                .map(m -> m.getStatus() == Membership.MembershipStatus.ACTIVE &&
                        EnumSet.of(Membership.Role.OWNER, Membership.Role.ADMIN, Membership.Role.EDITOR)
                                .contains(m.getRole()))
                .orElse(false);
    }

    public boolean canEditPensionFields(OAuth2User principal, Long pensionId) {
        Long userId = uidFrom(principal);
        if (userId == null) return false;
        if (isOwner(userId, pensionId)) return true;
        Pension p = pensions.findById(pensionId).orElse(null);
        if (p == null) return false;

        // Con owner_id ya asignado, los datos generales sólo los edita el owner.
        if (p.getOwner() != null) return false;

        // Compatibilidad con el esquema anterior basado en organizaciones.
        return memberships.findByOrgIdAndUserId(p.getOrg().getId(), userId)
                .map(m -> m.getStatus() == Membership.MembershipStatus.ACTIVE &&
                        EnumSet.of(Membership.Role.OWNER, Membership.Role.ADMIN).contains(m.getRole()))
                .orElse(false);
    }

    public boolean canUpdateAvailability(OAuth2User principal, Long pensionId) {
        Long userId = uidFrom(principal);
        return userId != null && canUpdateAvailability(userId, pensionId);
    }

    public boolean canRemovePension(OAuth2User principal, Long pensionId) {
        return canEditPensionFields(principal, pensionId); // OWNER/ADMIN
    }

    public boolean isOwner(OAuth2User principal, Long pensionId) {
        Long userId = uidFrom(principal);
        if (userId == null) return false;
        return this.isOwner(userId, pensionId);
    }

    public boolean isOwner(Long userId, Long pensionId) {
        if (userId == null) return false;
        Pension pension = pensions.findById(pensionId).orElse(null);
        if (pension == null) return false;

        // Modelo actual: owner explícito por pensión.
        if (pension.getOwner() != null) {
            return pension.getOwner().getId().equals(userId);
        }

        // Compatibilidad con pensiones creadas antes de incorporar owner_id.
        return memberships.findByOrgIdAndUserId(pension.getOrg().getId(), userId)
                .map(m -> m.getStatus() == Membership.MembershipStatus.ACTIVE
                        && m.getRole() == Membership.Role.OWNER)
                .orElse(false);
    }

    public boolean canManageMembers(@AuthenticationPrincipal OAuth2User principal, Long pensionId) {
        Long userId = uidFrom(principal);
        // hoy solo OWNER; si luego usás MANAGER, incluílo aquí
        return isOwner(userId, pensionId);
    }

    public boolean canUpdateAvailability(Long userId, Long pensionId) {
        if (isOwner(userId, pensionId)) return true;
        Pension p = pensions.findById(pensionId).orElse(null);
        if (p == null) return false;

        boolean pensionMemberPermission = members.findByPensionIdAndUserId(pensionId, userId)
                .map(PensionMember::getRole)
                .map(role -> role == PensionRole.AVAIL_ONLY || role == PensionRole.MANAGER)
                .orElse(false);
        if (pensionMemberPermission) return true;

        if (p.getOwner() != null) return false;

        // Compatibilidad con el esquema anterior basado en membresías de organización.
        return memberships.findByOrgIdAndUserId(p.getOrg().getId(), userId)
                .map(m -> m.getStatus() == Membership.MembershipStatus.ACTIVE &&
                        EnumSet.of(Membership.Role.OWNER, Membership.Role.ADMIN, Membership.Role.EDITOR).contains(m.getRole()))
                .orElse(false);
    }

    public boolean canEditPension(Long userId, Long pensionId) {
        // para editar datos generales
        return isOwner(userId, pensionId) /*|| has MANAGER */;
    }

    public boolean canViewPension(OAuth2User principal, Long pensionId) {
        Long userId = uidFrom(principal);
        if (userId == null) return false;
        if (isOwner(userId, pensionId)) return true;

        Pension p = pensions.findById(pensionId).orElse(null);
        if (p == null) return false;

        if (members.findByPensionIdAndUserId(pensionId, userId).isPresent()) return true;

        if (p.getOwner() != null) return false;

        boolean legacyOrgAccess = memberships.findByOrgIdAndUserId(p.getOrg().getId(), userId)
                .map(m -> m.getStatus() == Membership.MembershipStatus.ACTIVE)
                .orElse(false);
        return legacyOrgAccess;
    }

}
