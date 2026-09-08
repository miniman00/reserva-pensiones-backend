package uy.pensiones.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uy.pensiones.config.AppProperties;
import uy.pensiones.enums.LaunchCampaignBeneficiaryStatus;
import uy.pensiones.enums.NotificationType;
import uy.pensiones.mail.MailService;
import uy.pensiones.model.LaunchCampaignBeneficiary;
import uy.pensiones.model.User;
import uy.pensiones.repo.LaunchCampaignBeneficiaryRepository;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;

/**
 * Ciclo de vida operativo de los beneficios Fundador.
 *
 * Los avisos son idempotentes gracias al dedup_key de user_notifications. El correo se encola
 * únicamente cuando se creó la notificación del portal, por lo que varias instancias del backend
 * pueden ejecutar este scheduler sin duplicar mensajes al propietario.
 */
@Service
public class FounderBenefitLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(FounderBenefitLifecycleService.class);
    private static final String COMMERCIAL_PATH = "/profile/commercial";
    private static final int MAX_REMINDER_DAYS = 30;

    private final LaunchCampaignBeneficiaryRepository beneficiaries;
    private final NotificationService notifications;
    private final MailService mail;
    private final AppProperties appProperties;
    private final ZoneId businessZone;
    private final DateTimeFormatter dateFormatter;

    public FounderBenefitLifecycleService(LaunchCampaignBeneficiaryRepository beneficiaries,
                                          NotificationService notifications,
                                          MailService mail,
                                          AppProperties appProperties,
                                          @Value("${app.founder-benefit-notifications.zone:America/Montevideo}") String zone) {
        this.beneficiaries = beneficiaries;
        this.notifications = notifications;
        this.mail = mail;
        this.appProperties = appProperties;
        this.businessZone = safeZone(zone);
        this.dateFormatter = DateTimeFormatter.ofPattern("d 'de' MMMM 'de' uuuu", new Locale("es", "UY"));
    }

    @Scheduled(
            cron = "${app.founder-benefit-notifications.cron:0 10 * * * *}",
            zone = "${app.founder-benefit-notifications.zone:America/Montevideo}"
    )
    @Transactional
    public void maintainFounderBenefits() {
        process(OffsetDateTime.now(ZoneOffset.UTC));
    }

    void process(OffsetDateTime now) {
        OffsetDateTime instant = now == null ? OffsetDateTime.now(ZoneOffset.UTC) : now;
        List<LaunchCampaignBeneficiary> candidates = beneficiaries.findLifecycleCandidates(
                FounderLaunchCampaignService.FOUNDER_CAMPAIGN_CODE,
                LaunchCampaignBeneficiaryStatus.ACTIVE,
                instant.plusDays(MAX_REMINDER_DAYS + 1L));
        for (LaunchCampaignBeneficiary beneficiary : candidates) {
            processCandidate(beneficiary, instant);
        }
    }

    private void processCandidate(LaunchCampaignBeneficiary beneficiary, OffsetDateTime now) {
        if (beneficiary == null || beneficiary.getId() == null || beneficiary.getExpiresAt() == null) return;
        User owner = beneficiary.getUser();
        if (owner == null || owner.getId() == null || owner.isSuspended()) return;

        if (!beneficiary.getExpiresAt().isAfter(now)) {
            sendExpired(beneficiary, owner);
            return;
        }

        long daysRemaining = ChronoUnit.DAYS.between(
                now.atZoneSameInstant(businessZone).toLocalDate(),
                beneficiary.getExpiresAt().atZoneSameInstant(businessZone).toLocalDate());
        int milestone = reminderMilestone(daysRemaining);
        if (milestone == 0) return;
        sendReminder(beneficiary, owner, milestone, daysRemaining);
    }

    private void sendReminder(LaunchCampaignBeneficiary beneficiary, User owner, int milestone, long daysRemaining) {
        long visibleDays = Math.max(1, daysRemaining);
        String expiration = formattedExpiration(beneficiary);
        String title = switch (milestone) {
            case 7 -> "Últimos " + visibleDays + " días de tu beneficio Fundador";
            case 14 -> "Tu beneficio Fundador vence en " + visibleDays + " días";
            default -> "Tu beneficio Fundador vence en " + visibleDays + " días";
        };
        String message = "Tu beneficio de Propietario Fundador vence el " + expiration
                + ". Después comienza el período de gracia comercial. Elige un plan antes de que finalice "
                + "para mantener tus publicaciones visibles sin interrupciones.";
        String dedupKey = dedupKey(beneficiary, "reminder-" + milestone);
        notifyOnce(beneficiary, owner, NotificationType.FOUNDER_BENEFIT_EXPIRING, title, message, dedupKey);
    }

    private void sendExpired(LaunchCampaignBeneficiary beneficiary, User owner) {
        String title = "Tu beneficio de Propietario Fundador finalizó";
        String message = "Tu beneficio de lanzamiento finalizó el " + formattedExpiration(beneficiary)
                + ". Entraste en el período de gracia. Contrata un plan antes de que termine para evitar "
                + "que tus publicaciones sean pausadas automáticamente.";
        notifyOnce(beneficiary, owner, NotificationType.FOUNDER_BENEFIT_EXPIRED,
                title, message, dedupKey(beneficiary, "expired"));
    }

    private void notifyOnce(LaunchCampaignBeneficiary beneficiary,
                            User owner,
                            NotificationType type,
                            String title,
                            String message,
                            String dedupKey) {
        if (!notifications.createOnce(owner, type, title, message, COMMERCIAL_PATH, dedupKey)) return;
        if (owner.getEmail() == null || owner.getEmail().isBlank()) return;
        try {
            mail.sendFounderBenefitNotice(owner.getEmail(), owner.getName(), title, message, absoluteCommercialLink());
        } catch (RuntimeException ex) {
            // La notificación del portal permanece como canal principal; el outbox es complementario.
            log.warn("No se pudo encolar aviso Fundador. beneficiaryId={}, userId={}, type={}, error={}",
                    beneficiary.getId(), owner.getId(), type, ex.getMessage());
        }
    }

    private int reminderMilestone(long daysRemaining) {
        if (daysRemaining < 1 || daysRemaining > MAX_REMINDER_DAYS) return 0;
        if (daysRemaining <= 7) return 7;
        if (daysRemaining <= 14) return 14;
        return 30;
    }

    private String formattedExpiration(LaunchCampaignBeneficiary beneficiary) {
        return beneficiary.getExpiresAt().atZoneSameInstant(businessZone).toLocalDate().format(dateFormatter);
    }

    private String dedupKey(LaunchCampaignBeneficiary beneficiary, String milestone) {
        return "founder-benefit:" + beneficiary.getId() + ":" + milestone + ":"
                + beneficiary.getExpiresAt().toInstant().getEpochSecond();
    }

    private String absoluteCommercialLink() {
        String base = appProperties.getFrontendUrl();
        if (base == null || base.isBlank()) base = "http://localhost:5173";
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + COMMERCIAL_PATH;
    }

    private ZoneId safeZone(String value) {
        try {
            return ZoneId.of(value == null || value.isBlank() ? "America/Montevideo" : value.trim());
        } catch (RuntimeException ex) {
            log.warn("Zona de avisos Fundador inválida '{}'; se usará America/Montevideo", value);
            return ZoneId.of("America/Montevideo");
        }
    }
}
