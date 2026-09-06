package uy.pensiones.service;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.web.server.ResponseStatusException;
import uy.pensiones.enums.InquiryStatus;
import uy.pensiones.enums.UserRole;
import uy.pensiones.model.Pension;
import uy.pensiones.model.PensionInquiry;
import uy.pensiones.model.User;
import uy.pensiones.repo.PensionInquiryRepository;
import uy.pensiones.security.Authz;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OwnerInquiryHistoryServiceTest {

    @Test
    void historyRequiresEntitlementAndReturnsPagedOwnedInquiries() {
        PensionInquiryRepository inquiries = mock(PensionInquiryRepository.class);
        PensionService pensions = mock(PensionService.class);
        Authz authz = mock(Authz.class);
        OwnerEntitlementService entitlements = mock(OwnerEntitlementService.class);
        PensionInquiryConversationService conversations = mock(PensionInquiryConversationService.class);
        OwnerInquiryHistoryService service = new OwnerInquiryHistoryService(inquiries, pensions, authz, entitlements, conversations);

        User owner = User.builder().id(10L).role(UserRole.OWNER).build();
        Pension pension = Pension.builder().id(7L).name("Centro").build();
        PensionInquiry inquiry = PensionInquiry.builder()
                .id(99L).pension(pension).requester(User.builder().id(20L).build())
                .contactName("Ana").status(InquiryStatus.CONTACTED)
                .createdAt(OffsetDateTime.now()).updatedAt(OffsetDateTime.now()).build();

        when(pensions.myPensions(10L)).thenReturn(List.of(pension));
        when(authz.isOwner(10L, 7L)).thenReturn(true);
        when(inquiries.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(inquiry)));
        when(inquiries.count(any(Specification.class))).thenReturn(1L, 0L, 1L, 0L, 0L, 0L, 0L, 0L);
        when(conversations.unreadCounts(List.of(99L), owner)).thenReturn(Map.of(99L, 2L));

        var result = service.history(owner, 0, 20, null, null, null, null, null, null);

        verify(entitlements).requireInquiryHistory(10L);
        assertEquals(1, result.items().size());
        assertEquals(2, result.items().get(0).unreadMessages());
        assertEquals(1, result.summary().total());
        assertEquals(1, result.summary().contactedCount());
        assertEquals("Centro", result.pensions().get(0).name());
    }

    @Test
    void historyDoesNotAllowFilteringByCollaboratorPension() {
        PensionInquiryRepository inquiries = mock(PensionInquiryRepository.class);
        PensionService pensions = mock(PensionService.class);
        Authz authz = mock(Authz.class);
        OwnerEntitlementService entitlements = mock(OwnerEntitlementService.class);
        PensionInquiryConversationService conversations = mock(PensionInquiryConversationService.class);
        OwnerInquiryHistoryService service = new OwnerInquiryHistoryService(inquiries, pensions, authz, entitlements, conversations);

        User owner = User.builder().id(10L).role(UserRole.OWNER).build();
        Pension collaboratorPension = Pension.builder().id(7L).name("Ajena").build();
        when(pensions.myPensions(10L)).thenReturn(List.of(collaboratorPension));
        when(authz.isOwner(10L, 7L)).thenReturn(false);

        assertThrows(ResponseStatusException.class,
                () -> service.history(owner, 0, 20, 7L, null, null, null, null, null));
        verify(entitlements).requireInquiryHistory(10L);
        verifyNoInteractions(inquiries);
    }
}
