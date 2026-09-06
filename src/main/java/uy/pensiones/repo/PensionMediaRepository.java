package uy.pensiones.repo;

import org.springframework.data.jpa.repository.*;
import uy.pensiones.model.PensionMedia;

import java.util.List;
import java.util.Optional;

public interface PensionMediaRepository extends JpaRepository<PensionMedia, Long> {
    List<PensionMedia> findByPensionIdOrderBySortOrderAscIdAsc(Long pensionId);
    Optional<PensionMedia> findByIdAndPensionId(Long id, Long pensionId);

    boolean existsByPensionIdAndKind(Long pensionId, PensionMedia.Kind kind);
    boolean existsByPensionIdAndKindAndFilename(Long pensionId, PensionMedia.Kind kind, String filename);
    long countByPensionIdAndKind(Long pensionId, PensionMedia.Kind kind);

    @Query("select coalesce(max(m.sortOrder), 0) from PensionMedia m where m.pension.id = :pensionId")
    Integer lastSort(Long pensionId);

    interface StoredFileReference {
        Long getPensionId();
        String getFilename();
    }

    @Query("select m.pension.id as pensionId, m.filename as filename from PensionMedia m where m.filename is not null")
    List<StoredFileReference> findStoredFileReferences();
}
