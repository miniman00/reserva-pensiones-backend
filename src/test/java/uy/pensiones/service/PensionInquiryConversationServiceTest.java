package uy.pensiones.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import uy.pensiones.enums.InquiryClosureReason;
import uy.pensiones.enums.InquiryMessageSenderRole;
import uy.pensiones.enums.InquiryStatus;
import uy.pensiones.enums.NotificationType;
import uy.pensiones.model.Pension;
import uy.pensiones.model.PensionInquiry;
import uy.pensiones.model.PensionInquiryMessage;
import uy.pensiones.model.User;
import uy.pensiones.repo.PensionInquiryMessageRepository;
import uy.pensiones.repo.PensionInquiryRepository;
import uy.pensiones.security.Authz;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PensionInquiryConversationServiceTest {

    private PensionInquiryRepository inquiries;
    private PensionInquiryMessageRepository messages;
    private Authz authz;
    private NotificationService notifications;
    private PensionInquiryConversationService service;

    @BeforeEach
    void setUp() {
        inquiries = mock(PensionInquiryRepository.class);
        messages = mock(PensionInquiryMessageRepository.class);
        authz = mock(Authz.class);
        notifications = mock(NotificationService.class);
        service = new PensionInquiryConversationService(inquiries, messages, authz, notifications);
    }

    @Test
    void requesterCanReadConversationWithoutSeeingOtherUserData() {
        User requester = user(7L);
        PensionInquiry inquiry = inquiry(10L, requester, InquiryStatus.NEW);
        PensionInquiryMessage message = PensionInquiryMessage.builder()
                .id(20L)
                .inquiry(inquiry)
                .sender(requester)
                .senderRole(InquiryMessageSenderRole.REQUESTER)
                .body("¿Sigue disponible?")
                .createdAt(OffsetDateTime.parse("2026-09-01T20:00:00-03:00"))
                .build();
        when(inquiries.findConversationById(10L)).thenReturn(Optional.of(inquiry));
        when(messages.findByInquiryIdOrderByCreatedAtAscIdAsc(10L)).thenReturn(List.of(message));

        var result = service.conversation(10L, requester);

        assertTrue(result.canReply());
        assertEquals(InquiryStatus.NEW, result.status());
        assertEquals(1, result.messages().size());
        assertEquals(InquiryMessageSenderRole.REQUESTER, result.messages().get(0).senderRole());
        assertEquals("¿Sigue disponible?", result.messages().get(0).body());
    }

    @Test
    void firstOwnerReplyMarksInquiryContactedAndNotifiesRequester() {
        User owner = user(2L);
        User requester = user(7L);
        PensionInquiry inquiry = inquiry(10L, requester, InquiryStatus.NEW);
        inquiry.getPension().setOwner(owner);
        when(inquiries.findConversationById(10L)).thenReturn(Optional.of(inquiry));
        when(authz.isOwner(2L, 50L)).thenReturn(true);
        when(messages.save(any(PensionInquiryMessage.class))).thenAnswer(invocation -> {
            PensionInquiryMessage value = invocation.getArgument(0);
            value.setId(21L);
            value.setCreatedAt(OffsetDateTime.parse("2026-09-01T21:00:00-03:00"));
            return value;
        });

        var result = service.send(10L, owner, "  Sí, sigue disponible.  ");

        assertEquals(InquiryStatus.CONTACTED, result.status());
        assertEquals(InquiryMessageSenderRole.OWNER, result.message().senderRole());
        assertEquals("Sí, sigue disponible.", result.message().body());
        verify(messages).save(argThat(saved -> saved.getRecipient() != null && saved.getRecipient().getId().equals(7L)));
        verify(inquiries).save(inquiry);
        verify(notifications).create(
                eq(requester),
                eq(NotificationType.INQUIRY_MESSAGE_RECEIVED),
                anyString(),
                anyString(),
                eq("/profile/sent-inquiries?inquiryId=10")
        );
    }

    @Test
    void requesterReplyReopensClosedConversationAndNotifiesOwner() {
        User owner = user(2L);
        User requester = user(7L);
        PensionInquiry inquiry = inquiry(10L, requester, InquiryStatus.CLOSED);
        inquiry.setClosureReason(InquiryClosureReason.NO_AVAILABILITY);
        inquiry.setClosedAt(OffsetDateTime.now().minusDays(1));
        inquiry.getPension().setOwner(owner);
        when(inquiries.findConversationById(10L)).thenReturn(Optional.of(inquiry));
        when(messages.save(any(PensionInquiryMessage.class))).thenAnswer(invocation -> {
            PensionInquiryMessage value = invocation.getArgument(0);
            value.setId(22L);
            value.setCreatedAt(OffsetDateTime.now());
            return value;
        });

        var result = service.send(10L, requester, "Tengo otra consulta");

        assertEquals(InquiryStatus.CONTACTED, result.status());
        assertNull(inquiry.getClosureReason());
        assertNull(inquiry.getClosedAt());
        verify(messages).save(argThat(saved -> saved.getRecipient() != null && saved.getRecipient().getId().equals(2L)));
        verify(inquiries).save(inquiry);
        verify(notifications).create(
                eq(owner),
                eq(NotificationType.INQUIRY_MESSAGE_RECEIVED),
                anyString(),
                anyString(),
                eq("/profile/inquiries?inquiryId=10")
        );
    }

    @Test
    void participantMarksOnlyMessagesAddressedToThemAsRead() {
        User requester = user(7L);
        PensionInquiry inquiry = inquiry(10L, requester, InquiryStatus.CONTACTED);
        when(inquiries.findConversationById(10L)).thenReturn(Optional.of(inquiry));

        service.markRead(10L, requester);

        verify(messages).markUnreadAsRead(eq(10L), eq(7L), any(OffsetDateTime.class));
    }

    @Test
    void unrelatedUserCannotMarkConversationAsRead() {
        User unrelated = user(99L);
        PensionInquiry inquiry = inquiry(10L, user(7L), InquiryStatus.NEW);
        when(inquiries.findConversationById(10L)).thenReturn(Optional.of(inquiry));
        when(authz.isOwner(99L, 50L)).thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.markRead(10L, unrelated));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        verify(messages, never()).markUnreadAsRead(anyLong(), anyLong(), any());
    }

    @Test
    void anonymousInquiryCannotReceiveInternalMessages() {
        User owner = user(2L);
        PensionInquiry inquiry = inquiry(10L, null, InquiryStatus.NEW);
        inquiry.getPension().setOwner(owner);
        when(inquiries.findConversationById(10L)).thenReturn(Optional.of(inquiry));
        when(authz.isOwner(2L, 50L)).thenReturn(true);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.send(10L, owner, "Respuesta"));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        verify(messages, never()).save(any());
    }

    @Test
    void unrelatedUserGetsNotFoundInsteadOfInquiryDisclosure() {
        User unrelated = user(99L);
        PensionInquiry inquiry = inquiry(10L, user(7L), InquiryStatus.NEW);
        when(inquiries.findConversationById(10L)).thenReturn(Optional.of(inquiry));
        when(authz.isOwner(99L, 50L)).thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.conversation(10L, unrelated));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        assertEquals("Consulta no encontrada", ex.getReason());
    }

    private PensionInquiry inquiry(Long id, User requester, InquiryStatus status) {
        return PensionInquiry.builder()
                .id(id)
                .pension(Pension.builder().id(50L).name("Pensión Centro").build())
                .requester(requester)
                .contactName("Ana")
                .status(status)
                .build();
    }

    private User user(Long id) {
        return User.builder().id(id).build();
    }
}
