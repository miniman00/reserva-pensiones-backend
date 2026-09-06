package uy.pensiones.repo;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uy.pensiones.enums.InquiryStatus;
import uy.pensiones.model.PensionInquiry;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PensionInquiryRepository extends JpaRepository<PensionInquiry, Long>, JpaSpecificationExecutor<PensionInquiry> {

    @EntityGraph(attributePaths = {"pension", "requester"})
    List<PensionInquiry> findByPensionIdInOrderByCreatedAtDesc(Collection<Long> pensionIds);

    @EntityGraph(attributePaths = {"pension", "requester"})
    Optional<PensionInquiry> findWithPensionById(Long id);

    @EntityGraph(attributePaths = {"pension", "pension.owner", "pension.createdBy", "requester"})
    Optional<PensionInquiry> findConversationById(Long id);

    @EntityGraph(attributePaths = {"pension", "requester"})
    List<PensionInquiry> findByRequesterIdOrderByCreatedAtDesc(Long requesterId);

    long countByRequesterId(Long requesterId);

    List<PensionInquiry> findTop20ByPensionIdAndCreatedAtAfterOrderByCreatedAtDesc(
            Long pensionId, OffsetDateTime createdAt);

    @EntityGraph(attributePaths = "pension")
    List<PensionInquiry> findTop5ByPensionIdInOrderByCreatedAtDesc(Collection<Long> pensionIds);

    @Query("""
            select i.pension.id as pensionId, i.status as status, count(i.id) as total
            from PensionInquiry i
            where i.pension.id in :pensionIds
            group by i.pension.id, i.status
            """)
    List<PensionInquiryStatusCount> countByPensionIdsAndStatus(@Param("pensionIds") Collection<Long> pensionIds);


    @Query("""
            select i.pension.id as pensionId, count(i.id) as total
            from PensionInquiry i
            where i.pension.id in :pensionIds
              and i.createdAt >= :since
            group by i.pension.id
            """)
    List<PensionInquiryCount> countByPensionIdsSince(@Param("pensionIds") Collection<Long> pensionIds,
                                                     @Param("since") OffsetDateTime since);

    interface PensionInquiryCount {
        Long getPensionId();
        long getTotal();
    }

    interface PensionInquiryStatusCount {
        Long getPensionId();
        InquiryStatus getStatus();
        long getTotal();
    }
}
