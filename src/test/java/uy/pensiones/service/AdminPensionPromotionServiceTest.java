package uy.pensiones.service;

import org.junit.jupiter.api.Test;
import uy.pensiones.enums.PensionPromotionEffectiveStatus;
import uy.pensiones.enums.PensionPromotionStatus;
import uy.pensiones.model.PensionPromotion;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AdminPensionPromotionServiceTest {

    private final AdminPensionPromotionService service =
            new AdminPensionPromotionService(null, null, null, null, null);

    @Test
    void effectiveStatusIsDerivedFromDatesWithoutBackgroundJob() {
        OffsetDateTime now = OffsetDateTime.parse("2026-08-30T12:00:00Z");

        var scheduled = PensionPromotion.builder().status(PensionPromotionStatus.ACTIVE)
                .startsAt(now.plusDays(1)).endsAt(now.plusDays(8)).build();
        var active = PensionPromotion.builder().status(PensionPromotionStatus.ACTIVE)
                .startsAt(now.minusDays(1)).endsAt(now.plusDays(6)).build();
        var expired = PensionPromotion.builder().status(PensionPromotionStatus.ACTIVE)
                .startsAt(now.minusDays(8)).endsAt(now.minusSeconds(1)).build();
        var cancelled = PensionPromotion.builder().status(PensionPromotionStatus.CANCELLED)
                .startsAt(now.minusDays(1)).endsAt(now.plusDays(6)).build();

        assertEquals(PensionPromotionEffectiveStatus.SCHEDULED, service.effectiveStatus(scheduled, now));
        assertEquals(PensionPromotionEffectiveStatus.ACTIVE, service.effectiveStatus(active, now));
        assertEquals(PensionPromotionEffectiveStatus.EXPIRED, service.effectiveStatus(expired, now));
        assertEquals(PensionPromotionEffectiveStatus.CANCELLED, service.effectiveStatus(cancelled, now));
    }

    @Test
    void legacyPromotionWithoutEndDateRemainsActiveUntilCancelled() {
        OffsetDateTime now = OffsetDateTime.parse("2026-08-30T12:00:00Z");
        var legacy = PensionPromotion.builder().status(PensionPromotionStatus.ACTIVE)
                .startsAt(now.minusYears(1)).endsAt(null).build();

        assertEquals(PensionPromotionEffectiveStatus.ACTIVE, service.effectiveStatus(legacy, now));
    }
}
