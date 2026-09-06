package uy.pensiones.service;

import org.springframework.stereotype.Service;
import uy.pensiones.config.CatalogQualityProperties;
import uy.pensiones.enums.PensionStatus;
import uy.pensiones.model.Pension;
import uy.pensiones.model.User;
import uy.pensiones.repo.PensionRepository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;

@Service
public class PensionCatalogQualityService {

    private final CatalogQualityProperties properties;
    private final PensionRepository pensions;

    public PensionCatalogQualityService(CatalogQualityProperties properties, PensionRepository pensions) {
        this.properties = properties;
        this.pensions = pensions;
    }

    public OffsetDateTime publicAvailabilityCutoff() {
        return OffsetDateTime.now(ZoneOffset.UTC)
                .minusDays(properties.getMaxPublicAvailabilityAgeDays());
    }

    public OffsetDateTime publicVisibleUntil(Pension pension) {
        if (pension == null || pension.getAvailabilityUpdatedAt() == null) return null;
        return pension.getAvailabilityUpdatedAt()
                .plusDays(properties.getMaxPublicAvailabilityAgeDays());
    }

    public boolean hasFreshPublicAvailability(Pension pension) {
        if (pension == null || pension.getAvailabilityUpdatedAt() == null) return false;
        return !pension.getAvailabilityUpdatedAt().isBefore(publicAvailabilityCutoff());
    }

    public boolean isPubliclyVisible(Pension pension) {
        if (pension == null || pension.getStatus() != PensionStatus.PUBLISHED
                || Boolean.TRUE.equals(pension.getModerationBlocked())) return false;
        User responsible = pension.getOwner() != null ? pension.getOwner() : pension.getCreatedBy();
        return responsible != null && !responsible.isSuspended() && hasFreshPublicAvailability(pension);
    }

    /**
     * Señal de calidad, no bloqueo: una misma persona puede gestionar legítimamente
     * más de una unidad en la misma dirección, por eso solo alimenta el checklist.
     */
    public boolean hasPotentialDuplicate(Pension pension) {
        if (pension == null || pension.getId() == null) return false;
        Long responsibleId = pension.getOwner() != null && pension.getOwner().getId() != null
                ? pension.getOwner().getId()
                : pension.getCreatedBy() == null ? null : pension.getCreatedBy().getId();
        String address = normalize(pension.getAddressLine1());
        String city = normalize(pension.getCity());
        if (responsibleId == null || address == null || city == null) return false;
        return pensions.existsPotentialDuplicate(pension.getId(), responsibleId, address, city);
    }

    public int maxPublicAvailabilityAgeDays() {
        return properties.getMaxPublicAvailabilityAgeDays();
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
