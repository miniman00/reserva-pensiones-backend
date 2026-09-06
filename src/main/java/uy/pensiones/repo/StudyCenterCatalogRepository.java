package uy.pensiones.repo;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import uy.pensiones.model.StudyCenterCatalog;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface StudyCenterCatalogRepository extends JpaRepository<StudyCenterCatalog, Long> {
    Optional<StudyCenterCatalog> findByNormalizedName(String normalizedName);
    List<StudyCenterCatalog> findByNormalizedNameIn(Collection<String> normalizedNames);
    Page<StudyCenterCatalog> findByNameContainingIgnoreCase(String q, Pageable pageable);
    List<StudyCenterCatalog> findByVerifiedTrueAndActiveTrueAndNameContainingIgnoreCase(String q, Pageable pageable);
}
