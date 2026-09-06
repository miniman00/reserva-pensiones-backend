package uy.pensiones.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uy.pensiones.model.PensionInquiryMessage;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

public interface PensionInquiryMessageRepository extends JpaRepository<PensionInquiryMessage, Long> {
    List<PensionInquiryMessage> findByInquiryIdOrderByCreatedAtAscIdAsc(Long inquiryId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update PensionInquiryMessage m
               set m.readAt = :readAt
             where m.inquiry.id = :inquiryId
               and m.recipient.id = :recipientId
               and m.readAt is null
            """)
    int markUnreadAsRead(@Param("inquiryId") Long inquiryId,
                         @Param("recipientId") Long recipientId,
                         @Param("readAt") OffsetDateTime readAt);

    @Query("""
            select m.inquiry.id as inquiryId, count(m.id) as unreadMessages
              from PensionInquiryMessage m
             where m.recipient.id = :recipientId
               and m.readAt is null
               and m.inquiry.id in :inquiryIds
             group by m.inquiry.id
            """)
    List<InquiryUnreadCount> countUnreadByInquiryIds(@Param("recipientId") Long recipientId,
                                                     @Param("inquiryIds") Collection<Long> inquiryIds);

    @Query("""
            select count(m.id)
              from PensionInquiryMessage m
             where m.recipient.id = :recipientId
               and m.readAt is null
               and m.inquiry.pension.id in :pensionIds
            """)
    long countUnreadReceived(@Param("recipientId") Long recipientId,
                             @Param("pensionIds") Collection<Long> pensionIds);

    @Query("""
            select count(m.id)
              from PensionInquiryMessage m
             where m.recipient.id = :recipientId
               and m.readAt is null
               and m.inquiry.requester.id = :requesterId
            """)
    long countUnreadSent(@Param("recipientId") Long recipientId,
                         @Param("requesterId") Long requesterId);

    interface InquiryUnreadCount {
        Long getInquiryId();
        long getUnreadMessages();
    }
}
