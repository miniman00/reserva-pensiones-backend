package uy.pensiones.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import uy.pensiones.enums.InquiryMessageSenderRole;
import uy.pensiones.enums.InquiryStatus;
import uy.pensiones.enums.NotificationType;
import uy.pensiones.model.PensionInquiry;
import uy.pensiones.model.PensionInquiryMessage;
import uy.pensiones.model.User;
import uy.pensiones.realtime.RealtimeEventService;
import uy.pensiones.repo.PensionInquiryMessageRepository;
import uy.pensiones.repo.PensionInquiryRepository;
import uy.pensiones.security.Authz;
import uy.pensiones.web.dto.PensionInquiryConversationDTO;
import uy.pensiones.web.dto.PensionInquiryMessageDTO;
import uy.pensiones.web.dto.PensionInquiryMessageSendResponse;
import uy.pensiones.web.dto.PensionInquiryUnreadSummaryDTO;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class PensionInquiryConversationService {

    private final PensionInquiryRepository inquiries;
    private final PensionInquiryMessageRepository messages;
    private final Authz authz;
    private final NotificationService notifications;
    private final RealtimeEventService realtimeEvents;

    public PensionInquiryConversationService(PensionInquiryRepository inquiries,
                                             PensionInquiryMessageRepository messages,
                                             Authz authz,
                                             NotificationService notifications,
                                             RealtimeEventService realtimeEvents) {
        this.inquiries = inquiries;
        this.messages = messages;
        this.authz = authz;
        this.notifications = notifications;
        this.realtimeEvents = realtimeEvents;
    }

    @Transactional(readOnly = true)
    public PensionInquiryConversationDTO conversation(Long inquiryId, User me) {
        PensionInquiry inquiry = loadParticipantInquiry(inquiryId, me);
        List<PensionInquiryMessageDTO> items = messages.findByInquiryIdOrderByCreatedAtAscIdAsc(inquiryId).stream()
                .map(PensionInquiryMessageDTO::of)
                .toList();
        return new PensionInquiryConversationDTO(
                inquiry.getId(),
                inquiry.getStatus(),
                inquiry.getRequester() != null,
                items
        );
    }

    @Transactional
    public void markRead(Long inquiryId, User me) {
        loadParticipantInquiry(inquiryId, me);
        int updated = messages.markUnreadAsRead(inquiryId, me.getId(), OffsetDateTime.now());
        if (updated > 0) {
            realtimeEvents.publishToUser(me.getId(), "INQUIRY_UNREAD_CHANGED", inquiryId, Map.of(
                    "inquiryId", inquiryId,
                    "reason", "READ"
            ));
        }
    }

    @Transactional(readOnly = true)
    public Map<Long, Long> unreadCounts(Collection<Long> inquiryIds, User me) {
        if (me == null || me.getId() == null || inquiryIds == null || inquiryIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return messages.countUnreadByInquiryIds(me.getId(), inquiryIds).stream()
                .collect(Collectors.toMap(
                        PensionInquiryMessageRepository.InquiryUnreadCount::getInquiryId,
                        PensionInquiryMessageRepository.InquiryUnreadCount::getUnreadMessages,
                        Long::max
                ));
    }

    @Transactional(readOnly = true)
    public PensionInquiryUnreadSummaryDTO unreadSummary(User me, Collection<Long> ownedPensionIds) {
        if (me == null || me.getId() == null) {
            return new PensionInquiryUnreadSummaryDTO(0, 0, 0);
        }
        long received = ownedPensionIds == null || ownedPensionIds.isEmpty()
                ? 0
                : messages.countUnreadReceived(me.getId(), ownedPensionIds);
        long sent = messages.countUnreadSent(me.getId(), me.getId());
        return new PensionInquiryUnreadSummaryDTO(received, sent, received + sent);
    }

    @Transactional
    public PensionInquiryMessageSendResponse send(Long inquiryId, User me, String rawMessage) {
        PensionInquiry inquiry = loadParticipantInquiry(inquiryId, me);
        InquiryMessageSenderRole role = participantRole(inquiry, me);
        if (inquiry.getRequester() == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Esta consulta no está asociada a una cuenta. Usa los datos de contacto para responder."
            );
        }

        String body = rawMessage == null ? "" : rawMessage.trim();
        if (body.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Escribe un mensaje");
        }
        if (body.length() > 2000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El mensaje no puede superar los 2000 caracteres");
        }

        InquiryStatus previousStatus = inquiry.getStatus();
        if (previousStatus == InquiryStatus.CLOSED || (role == InquiryMessageSenderRole.OWNER && previousStatus == InquiryStatus.NEW)) {
            inquiry.setStatus(InquiryStatus.CONTACTED);
            if (previousStatus == InquiryStatus.CLOSED) {
                inquiry.setClosureReason(null);
                inquiry.setClosedAt(null);
            }
            inquiries.save(inquiry);
        }

        User recipient = counterpart(inquiry, role);
        if (recipient != null && recipient.getId() != null && recipient.getId().equals(me.getId())) {
            recipient = null;
        }
        PensionInquiryMessage saved = messages.save(PensionInquiryMessage.builder()
                .inquiry(inquiry)
                .sender(me)
                .senderRole(role)
                .recipient(recipient)
                .body(body)
                .build());

        notifyCounterpart(inquiry, role, recipient);
        publishConversationChanged(inquiry, me, recipient, saved, role);
        return new PensionInquiryMessageSendResponse(PensionInquiryMessageDTO.of(saved), inquiry.getStatus());
    }


    private void publishConversationChanged(PensionInquiry inquiry,
                                            User sender,
                                            User recipient,
                                            PensionInquiryMessage message,
                                            InquiryMessageSenderRole role) {
        if (realtimeEvents == null || inquiry == null || inquiry.getId() == null || message == null) return;
        Map<String, Object> data = Map.of(
                "inquiryId", inquiry.getId(),
                "messageId", message.getId(),
                "status", inquiry.getStatus() == null ? "" : inquiry.getStatus().name(),
                "senderRole", role == null ? "" : role.name()
        );
        if (sender != null && sender.getId() != null) {
            realtimeEvents.publishToUser(sender.getId(), "INQUIRY_CONVERSATION_CHANGED", inquiry.getId(), data);
        }
        if (recipient != null && recipient.getId() != null) {
            realtimeEvents.publishToUser(recipient.getId(), "INQUIRY_CONVERSATION_CHANGED", inquiry.getId(), data);
            realtimeEvents.publishToUser(recipient.getId(), "INQUIRY_UNREAD_CHANGED", inquiry.getId(), Map.of(
                    "inquiryId", inquiry.getId(),
                    "reason", "MESSAGE_RECEIVED"
            ));
        }
    }

    private PensionInquiry loadParticipantInquiry(Long inquiryId, User me) {
        if (inquiryId == null || me == null || me.getId() == null) {
            throw notFound();
        }
        PensionInquiry inquiry = inquiries.findConversationById(inquiryId).orElseThrow(this::notFound);
        participantRole(inquiry, me);
        return inquiry;
    }

    private InquiryMessageSenderRole participantRole(PensionInquiry inquiry, User me) {
        if (inquiry.getRequester() != null && me.getId().equals(inquiry.getRequester().getId())) {
            return InquiryMessageSenderRole.REQUESTER;
        }
        if (authz.isOwner(me.getId(), inquiry.getPension().getId())) {
            return InquiryMessageSenderRole.OWNER;
        }
        throw notFound();
    }

    private User counterpart(PensionInquiry inquiry, InquiryMessageSenderRole role) {
        if (role == InquiryMessageSenderRole.OWNER) {
            return inquiry.getRequester();
        }
        return inquiry.getPension().getOwner() != null
                ? inquiry.getPension().getOwner()
                : inquiry.getPension().getCreatedBy();
    }

    private void notifyCounterpart(PensionInquiry inquiry, InquiryMessageSenderRole role, User recipient) {
        if (recipient == null) return;
        if (role == InquiryMessageSenderRole.OWNER) {
            notifications.create(
                    recipient,
                    NotificationType.INQUIRY_MESSAGE_RECEIVED,
                    "Nuevo mensaje sobre tu consulta",
                    "Recibiste una respuesta de " + inquiry.getPension().getName() + ".",
                    "/profile/sent-inquiries?inquiryId=" + inquiry.getId()
            );
            return;
        }

        notifications.create(
                recipient,
                NotificationType.INQUIRY_MESSAGE_RECEIVED,
                "Nuevo mensaje en una consulta",
                inquiry.getContactName() + " respondió sobre " + inquiry.getPension().getName() + ".",
                "/profile/inquiries?inquiryId=" + inquiry.getId()
        );
    }

    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Consulta no encontrada");
    }
}
