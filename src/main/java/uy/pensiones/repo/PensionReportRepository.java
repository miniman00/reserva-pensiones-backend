package uy.pensiones.repo;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uy.pensiones.enums.PensionReportReason;
import uy.pensiones.enums.PensionReportResolution;
import uy.pensiones.enums.PensionReportStatus;
import uy.pensiones.model.PensionReport;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

public interface PensionReportRepository extends JpaRepository<PensionReport, Long> {

    @Query("""
            select r from PensionReport r
            where (:status is null or r.status = :status)
              and (:pensionId is null or r.pension.id = :pensionId)
              and (:reason is null or r.reason = :reason)
              and (:resolution is null or r.resolution = :resolution)
            """)
    Page<PensionReport> searchAdmin(
            @Param("status") PensionReportStatus status,
            @Param("pensionId") Long pensionId,
            @Param("reason") PensionReportReason reason,
            @Param("resolution") PensionReportResolution resolution,
            Pageable pageable
    );

    List<PensionReport> findTop10ByPension_IdOrderByCreatedAtDesc(Long pensionId);

    long countByPension_Id(Long pensionId);

    long countByPension_IdAndStatusIn(Long pensionId, Collection<PensionReportStatus> statuses);

    boolean existsByPension_IdAndReporter_IdAndReasonAndStatusInAndCreatedAtAfter(
            Long pensionId,
            Long reporterUserId,
            PensionReportReason reason,
            Collection<PensionReportStatus> statuses,
            OffsetDateTime since
    );

    boolean existsByPension_IdAndReporterKeyHashAndReasonAndStatusInAndCreatedAtAfter(
            Long pensionId,
            String reporterKeyHash,
            PensionReportReason reason,
            Collection<PensionReportStatus> statuses,
            OffsetDateTime since
    );
}
