package uy.pensiones.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import uy.pensiones.model.Organization;

public interface OrganizationRepository extends JpaRepository<Organization, Long> {
}
