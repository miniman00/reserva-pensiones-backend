package uy.pensiones.web;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import uy.pensiones.enums.InquiryClosureReason;
import uy.pensiones.enums.InquiryStatus;
import uy.pensiones.enums.NotificationType;
import uy.pensiones.model.PensionInquiry;
import uy.pensiones.model.User;
import uy.pensiones.repo.PensionInquiryRepository;
import uy.pensiones.repo.UserRepository;
import uy.pensiones.security.Authz;
import uy.pensiones.service.NotificationService;
import uy.pensiones.service.PensionInquiryConversationService;
import uy.pensiones.service.PensionService;
import uy.pensiones.web.dto.PensionInquiryDTO;
import uy.pensiones.web.dto.PensionInquiryConversationDTO;
import uy.pensiones.web.dto.PensionInquiryMessageCreateRequest;
import uy.pensiones.web.dto.PensionInquiryMessageSendResponse;
import uy.pensiones.web.dto.PensionInquiryUnreadSummaryDTO;
import uy.pensiones.web.dto.SentPensionInquiryDTO;

import jakarta.validation.Valid;

import java.time.OffsetDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/inquiries")
public class PensionInquiryController {

    public record StatusRequest(InquiryStatus status, InquiryClosureReason closureReason) {}

    private final PensionInquiryRepository inquiries;
    private final PensionService pensionService;
    private final UserRepository users;
    private final Authz authz;
    private final NotificationService notifications;
    private final PensionInquiryConversationService conversations;

    public PensionInquiryController(PensionInquiryRepository inquiries,
                                    PensionService pensionService,
                                    UserRepository users,
                                    Authz authz,
                                    NotificationService notifications,
                                    PensionInquiryConversationService conversations) {
        this.inquiries = inquiries;
        this.pensionService = pensionService;
        this.users = users;
        this.authz = authz;
        this.notifications = notifications;
        this.conversations = conversations;
    }

    @GetMapping
    public List<PensionInquiryDTO> mine(@AuthenticationPrincipal OAuth2User principal) {
        User me = current(principal);
        List<Long> ownedPensionIds = pensionService.myPensions(me.getId()).stream()
                .filter(p -> authz.isOwner(me.getId(), p.getId()))
                .map(p -> p.getId())
                .toList();

        if (ownedPensionIds.isEmpty()) return List.of();

        List<PensionInquiry> found = inquiries.findByPensionIdInOrderByCreatedAtDesc(ownedPensionIds);
        var unread = conversations.unreadCounts(found.stream().map(PensionInquiry::getId).toList(), me);
        return found.stream()
                .map(inquiry -> PensionInquiryDTO.of(inquiry, unread.getOrDefault(inquiry.getId(), 0L)))
                .toList();
    }

    @GetMapping("/sent")
    public List<SentPensionInquiryDTO> sent(@AuthenticationPrincipal OAuth2User principal) {
        User me = current(principal);
        List<PensionInquiry> found = inquiries.findByRequesterIdOrderByCreatedAtDesc(me.getId());
        var unread = conversations.unreadCounts(found.stream().map(PensionInquiry::getId).toList(), me);
        return found.stream()
                .map(inquiry -> SentPensionInquiryDTO.of(inquiry, unread.getOrDefault(inquiry.getId(), 0L)))
                .toList();
    }

    @GetMapping("/unread-summary")
    public PensionInquiryUnreadSummaryDTO unreadSummary(@AuthenticationPrincipal OAuth2User principal) {
        User me = current(principal);
        List<Long> ownedPensionIds = pensionService.myPensions(me.getId()).stream()
                .filter(p -> authz.isOwner(me.getId(), p.getId()))
                .map(p -> p.getId())
                .toList();
        return conversations.unreadSummary(me, ownedPensionIds);
    }

    @GetMapping("/{id}/conversation")
    public PensionInquiryConversationDTO conversation(@AuthenticationPrincipal OAuth2User principal,
                                                       @PathVariable Long id) {
        return conversations.conversation(id, current(principal));
    }

    @PostMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markConversationRead(@AuthenticationPrincipal OAuth2User principal,
                                     @PathVariable Long id) {
        conversations.markRead(id, current(principal));
    }

    @PostMapping("/{id}/messages")
    public PensionInquiryMessageSendResponse sendMessage(@AuthenticationPrincipal OAuth2User principal,
                                                          @PathVariable Long id,
                                                          @Valid @RequestBody PensionInquiryMessageCreateRequest request) {
        return conversations.send(id, current(principal), request.message());
    }

    @PatchMapping("/{id}/status")
    public PensionInquiryDTO updateStatus(@AuthenticationPrincipal OAuth2User principal,
                                          @PathVariable Long id,
                                          @RequestBody StatusRequest request) {
        User me = current(principal);
        PensionInquiry inquiry = inquiries.findWithPensionById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Consulta no encontrada"));

        if (!authz.isOwner(me.getId(), inquiry.getPension().getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No tienes permisos sobre esta consulta");
        }
        if (request == null || request.status() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Debes indicar el estado");
        }

        InquiryStatus previous = inquiry.getStatus();
        InquiryClosureReason previousReason = inquiry.getClosureReason();
        applyStatus(inquiry, request.status(), request.closureReason());
        inquiry = inquiries.save(inquiry);

        if ((previous != inquiry.getStatus() || previousReason != inquiry.getClosureReason()) && inquiry.getRequester() != null) {
            notifications.create(
                    inquiry.getRequester(),
                    NotificationType.INQUIRY_STATUS_CHANGED,
                    "Actualización de tu consulta",
                    "Tu consulta por " + inquiry.getPension().getName() + " ahora está " + statusLabel(inquiry) + ".",
                    "/profile/sent-inquiries?inquiryId=" + inquiry.getId()
            );
        }

        long unread = conversations.unreadCounts(List.of(inquiry.getId()), me)
                .getOrDefault(inquiry.getId(), 0L);
        return PensionInquiryDTO.of(inquiry, unread);
    }

    private void applyStatus(PensionInquiry inquiry, InquiryStatus status, InquiryClosureReason closureReason) {
        if (status == InquiryStatus.CLOSED) {
            if (closureReason == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Debes indicar el motivo de cierre");
            }
            boolean wasClosed = inquiry.getStatus() == InquiryStatus.CLOSED;
            boolean correctingConvertedClosure = wasClosed
                    && inquiry.getClosureReason() == InquiryClosureReason.CONVERTED
                    && closureReason != InquiryClosureReason.CONVERTED;
            if (correctingConvertedClosure) {
                inquiry.setConvertedAt(null);
            }
            inquiry.setStatus(InquiryStatus.CLOSED);
            inquiry.setClosureReason(closureReason);
            if (!wasClosed || inquiry.getClosedAt() == null) {
                inquiry.setClosedAt(OffsetDateTime.now());
            }
            if (closureReason == InquiryClosureReason.CONVERTED && inquiry.getConvertedAt() == null) {
                inquiry.setConvertedAt(OffsetDateTime.now());
            }
            return;
        }

        inquiry.setStatus(status);
        inquiry.setClosureReason(null);
        inquiry.setClosedAt(null);
    }

    private String statusLabel(PensionInquiry inquiry) {
        return switch (inquiry.getStatus()) {
            case NEW -> "enviada";
            case CONTACTED -> "marcada como contactada";
            case CLOSED -> switch (inquiry.getClosureReason() == null ? InquiryClosureReason.OTHER : inquiry.getClosureReason()) {
                case NO_INTEREST -> "cerrada porque no continuó el interés";
                case NO_AVAILABILITY -> "cerrada por falta de disponibilidad";
                case CONVERTED -> "marcada como concretada";
                case OTHER -> "cerrada";
            };
        };
    }

    private User current(OAuth2User principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No autenticado");
        }

        User appUser = principal.getAttribute("appUser");
        if (appUser != null) {
            return users.findById(appUser.getId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Usuario no encontrado"));
        }

        String email = principal.getAttribute("email");
        if (email == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Usuario no encontrado");
        }
        return users.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Usuario no encontrado"));
    }
}
