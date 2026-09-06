package uy.pensiones.service;

import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;
import uy.pensiones.mail.MailService;
import uy.pensiones.model.Membership;
import uy.pensiones.repo.MembershipRepository;
import uy.pensiones.model.Organization;
import uy.pensiones.repo.OrganizationRepository;
import uy.pensiones.model.User;
import uy.pensiones.repo.UserRepository;

import java.util.List;

@Service
public class OrgService {
    private final OrganizationRepository organizations;
    private final MembershipRepository memberships;
    private final UserRepository users;
    private final MailService mail;

    public OrgService(OrganizationRepository organizations,
                      MembershipRepository memberships,
                      UserRepository users,
                      MailService mail) {
        this.organizations = organizations;
        this.memberships = memberships;
        this.users = users;
        this.mail = mail;
    }

    @Transactional
    public Organization createOrg(User owner, String name) {
        Organization org = Organization.builder()
                .name(name)
                .owner(owner)
                .build();
        org = organizations.save(org);

        Membership m = Membership.builder()
                .org(org)
                .user(owner)
                .role(Membership.Role.OWNER)
                .status(Membership.MembershipStatus.ACTIVE)
                .build();
        memberships.save(m);
        return org;
    }

    public List<Membership> myMemberships(Long userId) {
        return memberships.findByUserIdAndStatus(userId, Membership.MembershipStatus.ACTIVE);
    }

    public List<Membership> members(Long orgId) {
        return memberships.findByOrgId(orgId);
    }

    @Transactional
    public void changeRole(Long orgId, Long membershipId, Membership.Role role) {
        Membership m = memberships.findById(membershipId).orElseThrow();
        if (!m.getOrg().getId().equals(orgId)) throw new IllegalArgumentException("Membresía no pertenece a la org");
        if (m.getRole() == Membership.Role.OWNER) throw new IllegalStateException("No se puede cambiar rol del OWNER desde esta operación");
        m.setRole(role);
    }

    @Transactional
    public void removeMember(Long orgId, Long membershipId) {
        Membership m = memberships.findById(membershipId).orElseThrow();
        if (!m.getOrg().getId().equals(orgId)) throw new IllegalArgumentException("Membresía no pertenece a la org");
        if (m.getRole() == Membership.Role.OWNER) throw new IllegalStateException("No se puede remover al OWNER");
        m.setStatus(Membership.MembershipStatus.REMOVED);
        mail.sendUserRemoved(m.getUser().getEmail(), m.getOrg().getName());
    }
}
