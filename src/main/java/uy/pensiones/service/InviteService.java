package uy.pensiones.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uy.pensiones.enums.InviteStatus;
import uy.pensiones.enums.NotificationType;
import uy.pensiones.enums.PensionRole;
import uy.pensiones.mail.MailService;
import uy.pensiones.model.*;
import uy.pensiones.repo.*;

import java.time.OffsetDateTime;
import java.util.*;

@Service
public class InviteService {

    private final PensionRepository pensions;
    private final PensionInviteRepository invites;
    private final PensionMemberRepository members;
    private final UserRepository users;
    private final MailService mail;
    private final NotificationService notifications;
    private final OwnerEntitlementService entitlements;

    @Value("${app.frontend-url}")
    private String frontendUrl;

    @Value("${app.invites.ttl-days:14}")
    private int ttlDays;

    public InviteService(PensionRepository pensions, PensionInviteRepository invites,
                         PensionMemberRepository members, UserRepository users, MailService mail,
                         NotificationService notifications, OwnerEntitlementService entitlements) {
        this.pensions = pensions; this.invites = invites; this.members = members; this.users = users; this.mail = mail;
        this.notifications = notifications;
        this.entitlements = entitlements;
    }

    private String newToken() { return UUID.randomUUID().toString().replace("-", ""); }

    @Transactional
    public PensionInvite createInvite(Long pensionId, String emailRaw, PensionRole role, Long inviterUserId) {
        Pension pension = pensions.findById(pensionId).orElseThrow();
        String email = emailRaw.trim().toLowerCase();

        Optional<PensionInvite> existingPending =
                invites.findFirstByPensionIdAndEmailAndStatus(pensionId, email, InviteStatus.PENDING);
        boolean livePending = existingPending
                .map(PensionInvite::getExpiresAt)
                .map(expiresAt -> expiresAt.isAfter(OffsetDateTime.now()))
                .orElse(false);
        boolean existingMember = users.findByEmail(email)
                .flatMap(user -> members.findByPensionIdAndUserId(pensionId, user.getId()))
                .isPresent();
        if (!livePending && !existingMember) {
            entitlements.requireCanAddCollaborator(pensionId);
        }

        PensionInvite inv = existingPending.orElseGet(PensionInvite::new);

        inv.setPension(pension);
        inv.setEmail(email);
        inv.setRole(role == null ? PensionRole.AVAIL_ONLY : role);
        inv.setToken(newToken());
        inv.setStatus(InviteStatus.PENDING);
        inv.setCreatedByUserId(inviterUserId);
        inv.setExpiresAt(OffsetDateTime.now().plusDays(ttlDays));
        invites.save(inv);

        String acceptLink = frontendUrl.replaceAll("/$", "") + "/invite/" + inv.getToken();
        mail.sendInvite(email, pension.getName(), acceptLink);
        notifications.createForEmail(
                email,
                NotificationType.INVITE_RECEIVED,
                "Invitación a " + pension.getName(),
                "Te invitaron a colaborar en esta pensión como " + roleLabel(inv.getRole()) + ".",
                "/invite/" + inv.getToken()
        );
        return inv;
    }

    private String roleLabel(PensionRole role) {
        if (role == PensionRole.MANAGER) return "administrador";
        if (role == PensionRole.OWNER) return "propietario";
        return "colaborador de disponibilidad";
    }

    private String displayName(User user) {
        if (user == null) return "Un colaborador";
        if (user.getName() != null && !user.getName().isBlank()) return user.getName();
        if (user.getEmail() != null && !user.getEmail().isBlank()) return user.getEmail();
        return "Un colaborador";
    }

    @Transactional
    public void revokeInvite(Long pensionId, Long inviteId) {
        PensionInvite inv = invites.findByIdAndPensionId(inviteId, pensionId).orElseThrow();
        if (inv.getStatus() == InviteStatus.PENDING) {
            inv.setStatus(InviteStatus.REVOKED);
            invites.save(inv);
        }
    }

    @Transactional
    public Map<String,Object> accept(String token, Long currentUserId, String currentEmail) {
        PensionInvite inv = invites.findByToken(token).orElseThrow();
        if (inv.getStatus() != InviteStatus.PENDING)
            throw new IllegalStateException("La invitación no está activa.");
        if (inv.getExpiresAt().isBefore(OffsetDateTime.now()))
            throw new IllegalStateException("La invitación está expirada.");
        if (!inv.getEmail().equalsIgnoreCase(currentEmail))
            throw new IllegalStateException("La invitación es para otro correo.");

        PensionMember m = members.findByPensionIdAndUserId(inv.getPension().getId(), currentUserId)
                .orElseGet(PensionMember::new);
        m.setPension(inv.getPension());
        m.setUser(users.findById(currentUserId).orElseThrow());
        m.setRole(inv.getRole());
        members.save(m);

        inv.setStatus(InviteStatus.ACCEPTED);
        inv.setAcceptedByUserId(currentUserId);
        invites.save(inv);

        User owner = inv.getPension().getOwner() != null
                ? inv.getPension().getOwner()
                : inv.getPension().getCreatedBy();
        User acceptedUser = m.getUser();
        notifications.create(
                owner,
                NotificationType.INVITE_ACCEPTED,
                "Invitación aceptada",
                displayName(acceptedUser) + " aceptó la invitación a " + inv.getPension().getName() + ".",
                "/pensions/" + inv.getPension().getId() + "/members"
        );

        Map<String,Object> out = new HashMap<>();
        out.put("pensionId", inv.getPension().getId());
        out.put("pensionName", inv.getPension().getName());
        out.put("role", m.getRole().name());
        return out;
    }
}
