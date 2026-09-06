package uy.pensiones.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uy.pensiones.model.*;
import java.util.*;

public interface PensionMemberRepository extends JpaRepository<PensionMember, Long> {
    Optional<PensionMember> findByPensionIdAndUserId(Long pensionId, Long userId);
    @EntityGraph(attributePaths = {"pension", "user"})
    Optional<PensionMember> findByIdAndPensionId(Long id, Long pensionId);

    @EntityGraph(attributePaths = "user")
    List<PensionMember> findByPensionId(Long pensionId);

    @Query("select pm.pension.id from PensionMember pm where pm.user.id = :userId")
    List<Long> findPensionIdsByUserId(@Param("userId") Long userId);
}
