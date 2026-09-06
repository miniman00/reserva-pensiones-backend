package uy.pensiones.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import uy.pensiones.config.AppProperties;
import uy.pensiones.config.CatalogQualityProperties;
import uy.pensiones.enums.NotificationType;
import uy.pensiones.enums.PensionStatus;
import uy.pensiones.mail.MailService;
import uy.pensiones.model.Pension;
import uy.pensiones.model.User;
import uy.pensiones.repo.PensionRepository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Service
public class PensionCatalogMaintenanceService {

    private static final Logger log = LoggerFactory.getLogger(PensionCatalogMaintenanceService.class);

    private final PensionRepository pensions;
    private final NotificationService notifications;
    private final MailService mail;
    private final AppProperties appProperties;
    private final CatalogQualityProperties quality;

    public PensionCatalogMaintenanceService(PensionRepository pensions,
                                            NotificationService notifications,
                                            MailService mail,
                                            AppProperties appProperties,
                                            CatalogQualityProperties quality) {
        this.pensions = pensions;
        this.notifications = notifications;
        this.mail = mail;
        this.appProperties = appProperties;
        this.quality = quality;
    }

    @Scheduled(
            cron = "${app.catalog-quality.maintenance-cron:0 15 10 * * *}",
            zone = "${app.catalog-quality.maintenance-zone:UTC}"
    )
    public void maintainCatalogQuality() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        remindPublishedAvailability(now);
        remindAbandonedDrafts(now);
    }

    void remindPublishedAvailability(OffsetDateTime now) {
        OffsetDateTime candidateCutoff = now.minusDays(quality.getAvailabilityReminderDays());
        for (Pension pension : pensions.findAvailabilityMaintenanceCandidates(PensionStatus.PUBLISHED, candidateCutoff)) {
            User owner = responsibleUser(pension);
            OffsetDateTime reference = pension.getAvailabilityUpdatedAt();
            if (!eligible(owner) || reference == null) continue;

            long ageDays = Math.max(0, java.time.Duration.between(reference, now).toDays());
            NotificationType type;
            String title;
            String message;

            if (ageDays >= quality.getMaxPublicAvailabilityAgeDays()) {
                type = NotificationType.PENSION_AVAILABILITY_HIDDEN;
                title = "Tu pensión dejó de mostrarse hasta confirmar disponibilidad";
                message = "La disponibilidad de \"" + pension.getName() + "\" lleva " + ageDays
                        + " días sin confirmarse. Confirma los cupos actuales para que vuelva a aparecer automáticamente en las búsquedas.";
            } else if (ageDays >= quality.getAvailabilityStaleDays()) {
                type = NotificationType.PENSION_AVAILABILITY_STALE;
                title = "Confirma pronto la disponibilidad de tu pensión";
                message = "Hace " + ageDays + " días que no confirmas los cupos de \"" + pension.getName()
                        + "\". Si llega a " + quality.getMaxPublicAvailabilityAgeDays()
                        + " días, se ocultará temporalmente de las búsquedas.";
            } else {
                type = NotificationType.PENSION_AVAILABILITY_REVIEW_DUE;
                title = "Revisa la disponibilidad de tu pensión";
                message = "Hace " + ageDays + " días que no confirmas los cupos de \"" + pension.getName()
                        + "\". Revisarlos ayuda a que quienes buscan alojamiento encuentren información vigente.";
            }

            String link = editLink(pension.getId(), "pricing");
            notifyOwner(owner, pension, type, title, message, link, dedupKey(pension, type, reference));
        }
    }

    void remindAbandonedDrafts(OffsetDateTime now) {
        OffsetDateTime cutoff = now.minusDays(quality.getDraftReminderDays());
        for (Pension pension : pensions.findDraftMaintenanceCandidates(PensionStatus.DRAFT, cutoff)) {
            User owner = responsibleUser(pension);
            OffsetDateTime reference = pension.getUpdatedAt();
            if (!eligible(owner) || reference == null) continue;

            String link = editLink(pension.getId(), pension.getDraftStep());
            String title = "Tienes una publicación pendiente de terminar";
            String message = "El borrador \"" + pension.getName()
                    + "\" quedó sin cambios durante varios días. Puedes retomarlo exactamente desde donde lo dejaste.";
            notifyOwner(owner, pension, NotificationType.PENSION_DRAFT_REMINDER, title, message, link,
                    dedupKey(pension, NotificationType.PENSION_DRAFT_REMINDER, reference));
        }
    }

    private void notifyOwner(User owner, Pension pension, NotificationType type,
                             String title, String message, String link, String dedupKey) {
        if (!notifications.createOnce(owner, type, title, message, link, dedupKey)) return;
        try {
            mail.sendPensionMaintenanceReminder(
                    owner.getEmail(),
                    pension.getName(),
                    title,
                    message,
                    absoluteLink(link)
            );
        } catch (RuntimeException ex) {
            // La notificación dentro del portal ya quedó creada. El email es complementario.
            log.warn("No se pudo enviar recordatorio de mantenimiento. pensionId={}, userId={}, type={}, error={}",
                    pension.getId(), owner.getId(), type, ex.getMessage());
        }
    }

    private String dedupKey(Pension pension, NotificationType type, OffsetDateTime reference) {
        return "catalog-quality:" + pension.getId() + ":" + type.name() + ":" + reference.toInstant().getEpochSecond();
    }

    private User responsibleUser(Pension pension) {
        return pension.getOwner() != null ? pension.getOwner() : pension.getCreatedBy();
    }

    private boolean eligible(User user) {
        return user != null && user.getId() != null && !user.isSuspended()
                && user.getEmail() != null && !user.getEmail().isBlank();
    }

    private String editLink(Long pensionId, String step) {
        String safeStep = step == null || step.isBlank() ? "basic" : step;
        return "/profile/pensions/" + pensionId + "/edit?step=" + safeStep;
    }

    private String absoluteLink(String relative) {
        String base = appProperties.getFrontendUrl();
        if (base == null || base.isBlank()) base = "http://localhost:5173";
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + relative;
    }
}
